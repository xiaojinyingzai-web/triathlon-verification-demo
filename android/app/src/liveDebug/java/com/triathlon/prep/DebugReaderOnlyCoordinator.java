package com.triathlon.prep;
import com.triathlon.prep.core.*;import com.triathlon.prep.cefk.hardware.CefkCardReader;
import java.util.UUID;import java.util.concurrent.*;import java.util.function.Consumer;

/** Reader-only jobs, pinned local roster, explicit service gate and close-return acknowledgement. */
public final class DebugReaderOnlyCoordinator implements AutoCloseable {
 public interface Provider {
  ReaderDiagnosticInput input()throws Exception;RaceRoster roster()throws Exception;
  boolean serviceApproved();CefkCardReader.Backend reader(ReaderDiagnosticInput input)throws Exception;
 }
 private final Provider provider;private final Consumer<ReaderOnlyContract.Model> output;private final Object lock=new Object();
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"reader-only-prepare");t.setDaemon(true);return t;});
 private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"reader-only-timeout");t.setDaemon(true);return t;});
 private ReaderOnlyContract.Phase phase=ReaderOnlyContract.Phase.NEEDS_PRIVATE_INPUT;private Job current;private boolean closed;private long sequence,revokedResult;
 private static final class Job {final long token;final String id=UUID.randomUUID().toString();ReaderDiagnosticInput input;RaceRoster roster;CefkCardReader reader;ReaderOnlyContract.Model result;boolean cancelled,preparing=true,creating,created,finished,released,terminal;ScheduledFuture<?> deadline;Job(long token){this.token=token;}}
 public DebugReaderOnlyCoordinator(Provider provider,Consumer<ReaderOnlyContract.Model> output){if(provider==null||output==null)throw new IllegalArgumentException("Reader owner required");this.provider=provider;this.output=output;}
 public ReaderOnlyContract.Phase phase(){synchronized(lock){return phase;}}
 public boolean currentModel(ReaderOnlyContract.Model model){synchronized(lock){return model.sessionEpoch==sequence&&(model.maskedNumber.isEmpty()||model.sessionEpoch!=revokedResult);}}
 private void emit(Job job,ReaderOnlyContract.Model model){synchronized(lock){if(sequence!=job.token)return;if((job.cancelled||job.finished)&&model.phase!=ReaderOnlyContract.Phase.CLOSING&&model.phase!=ReaderOnlyContract.Phase.CLOSED&&model.phase!=ReaderOnlyContract.Phase.CLOSE_UNCONFIRMED)return;phase=model.phase;if(model.phase==ReaderOnlyContract.Phase.MATCHED||model.phase==ReaderOnlyContract.Phase.NOT_LISTED||model.phase==ReaderOnlyContract.Phase.NO_ROSTER||model.phase==ReaderOnlyContract.Phase.FAILED)job.result=model;}output.accept(new ReaderOnlyContract.Model(model.phase,model.maskedNumber,model.cardName,model.matchedName,model.bib,model.batchId,model.code,model.resultPhase,job.token));}
 private void state(Job job,ReaderOnlyContract.Phase phase,String code){emit(job,new ReaderOnlyContract.Model(phase,"","","","","",code));}
 public void prepare(){
  final Job job;synchronized(lock){if(closed||current!=null)return;job=new Job(++sequence);current=job;}
  worker.execute(()->{
   try{
    job.input=provider.input();job.roster=provider.roster();
    synchronized(lock){if(job.cancelled)return;}
    if(job.roster==null){state(job,ReaderOnlyContract.Phase.NO_ROSTER,"NO_ROSTER");finish(job);return;}
    if(!provider.serviceApproved()){job.input.close();job.input=null;state(job,ReaderOnlyContract.Phase.SERVICE_REVIEW_PENDING,"SERVICE_REVIEW_PENDING");return;}
    job.deadline=timer.schedule(()->cancelJob(job),60,TimeUnit.SECONDS);state(job,ReaderOnlyContract.Phase.PREPARED,"PREPARED");
   }catch(ReaderServiceApproval.InvalidApproval invalid){if(job.input!=null){job.input.close();job.input=null;}job.roster=null;state(job,ReaderOnlyContract.Phase.SERVICE_REVIEW_PENDING,"APPROVAL_"+invalid.reason.name());}
   catch(Exception|LinkageError error){state(job,ReaderOnlyContract.Phase.FAILED,"CONFIGURATION_FAILED");finish(job);}
   finally{synchronized(lock){job.preparing=false;if((job.cancelled||job.finished)&&!job.created&&!job.creating)job.released=true;}if(job.cancelled)finish(job);else terminal(job);}
  });
 }
 public void readOnce(){
  final Job job;synchronized(lock){job=current;if(closed||job==null||job.cancelled||phase!=ReaderOnlyContract.Phase.PREPARED)return;phase=ReaderOnlyContract.Phase.INITIALIZING;if(job.deadline!=null)job.deadline.cancel(false);}
  if(!provider.serviceApproved()){if(job.input!=null){job.input.close();job.input=null;}state(job,ReaderOnlyContract.Phase.SERVICE_REVIEW_PENDING,"SERVICE_REVIEW_PENDING");return;}
  state(job,ReaderOnlyContract.Phase.INITIALIZING,"INITIALIZING");
  job.reader=new CefkCardReader(()->{
   synchronized(lock){if(job.cancelled){job.released=true;throw new IllegalStateException("Cancelled");}job.creating=true;}
   try{
    final CefkCardReader.Backend delegate=provider.reader(job.input);
    if(delegate==null)throw new IllegalStateException("Reader unavailable");
    synchronized(lock){job.created=true;}
    return new CefkCardReader.Backend(){
     public void initialize(CefkCardReader.Events events)throws Exception{delegate.initialize(new CefkCardReader.Events(){public void initialized(){state(job,ReaderOnlyContract.Phase.WAITING_CARD,"WAITING_CARD");events.initialized();}public void card(CefkCardReader.Card card){events.card(card);}public void failed(CefkCardReader.Error error){events.failed(error);}});}
     public void read(CefkCardReader.Events events)throws Exception{delegate.read(events);}
     public void close()throws Exception{try{delegate.close();synchronized(lock){job.released=true;}terminal(job);}catch(Exception|LinkageError failure){state(job,ReaderOnlyContract.Phase.CLOSE_UNCONFIRMED,"CLOSE_UNCONFIRMED");throw failure;}}
    };
   }catch(Exception|LinkageError error){synchronized(lock){if(!job.created)job.released=true;}throw error;}
   finally{synchronized(lock){job.creating=false;}terminal(job);}
  },20000);
  job.reader.read(job.id,new CefkCardReader.Reply(){
   public void card(String id,CefkCardReader.Card card){try{
    synchronized(lock){if(job.cancelled)return;}
    String number=IdentityNumber.normalize(card.number());RaceRoster.Athlete match=job.roster.match(number);
    String masked="**************"+number.substring(14);String batch=job.roster.batchId;
    emit(job,new ReaderOnlyContract.Model(ReaderOnlyContract.Phase.READ_OK,masked,card.name(),"","",batch,"READ_OK"));
    emit(job,new ReaderOnlyContract.Model(match==null?ReaderOnlyContract.Phase.NOT_LISTED:ReaderOnlyContract.Phase.MATCHED,masked,card.name(),match==null?"":match.name,match==null?"":match.bib,batch,match==null?"NOT_LISTED":"MATCHED"));
   }catch(Exception error){state(job,ReaderOnlyContract.Phase.FAILED,"INVALID_CARD");}finally{card.close();finish(job);}}
   public void failed(String id,CefkCardReader.Error error){state(job,ReaderOnlyContract.Phase.FAILED,error.name());finish(job);}
  });
 }
 private void finish(Job job){synchronized(lock){job.finished=true;if(!job.preparing&&!job.created&&!job.creating)job.released=true;if(job.deadline!=null)job.deadline.cancel(false);}if(job.reader!=null)job.reader.close();terminal(job);if(!timer.isShutdown())timer.schedule(()->{synchronized(lock){if(job.terminal)return;}state(job,ReaderOnlyContract.Phase.CLOSE_UNCONFIRMED,"CLOSE_UNCONFIRMED");},15,TimeUnit.SECONDS);}
 private void terminal(Job job){
  synchronized(lock){if(job.terminal||!job.finished||!job.released||job.preparing)return;job.terminal=true;if(current==job)current=null;}
  if(job.input!=null){job.input.close();job.input=null;}job.roster=null;
  ReaderOnlyContract.Model result=job.result;job.result=null;
  if(!job.cancelled&&result!=null)emit(job,new ReaderOnlyContract.Model(ReaderOnlyContract.Phase.CLOSED,result.maskedNumber,result.cardName,result.matchedName,result.bib,result.batchId,"CLOSED",result.phase,job.token));else state(job,ReaderOnlyContract.Phase.CLOSED,"CLOSED");
 }
 private void cancelJob(Job job){
  synchronized(lock){if(job.terminal||current!=job)return;job.cancelled=true;revokedResult=job.token;job.result=null;}
  state(job,ReaderOnlyContract.Phase.CLOSING,"CLOSING");finish(job);
  timer.schedule(()->{synchronized(lock){if(job.terminal)return;}state(job,ReaderOnlyContract.Phase.CLOSE_UNCONFIRMED,"CLOSE_UNCONFIRMED");},15,TimeUnit.SECONDS);
 }
 public void cancel(){Job job;synchronized(lock){job=current;if(job==null)revokedResult=sequence;}if(job!=null)cancelJob(job);}
 @Override public void close(){synchronized(lock){if(closed)return;closed=true;}cancel();worker.shutdown();timer.shutdown();}
}
