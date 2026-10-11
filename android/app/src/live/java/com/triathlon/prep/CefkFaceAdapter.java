package com.triathlon.prep;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.cefk.face.FaceClient;
import java.util.Arrays;
import java.util.concurrent.*;

/** Maps protocol observations to explicit local decision parameters, never code00 to PASSED. */
public final class CefkFaceAdapter implements Verification.AlgorithmAdapter, AutoCloseable {
 public interface ImageEncoder { byte[] encode(byte[] normalizedPng)throws Exception; }
 public static final class Parameters {
  public final double liveThreshold,faceThreshold;public final Verification.ScoreRule liveRule,faceRule;public final boolean confirmed;
  public Parameters(double liveThreshold,Verification.ScoreRule liveRule,double faceThreshold,Verification.ScoreRule faceRule,boolean confirmed){
   if(!finite(liveThreshold)||!finite(faceThreshold)||liveRule==null||faceRule==null)throw new IllegalArgumentException("Explicit raw-0..100 thresholds and directions required");
   this.liveThreshold=liveThreshold;this.liveRule=liveRule;this.faceThreshold=faceThreshold;this.faceRule=faceRule;this.confirmed=confirmed;
  }
 }
 private final Verification.Mode mode;private final FaceClient client;private final Parameters parameters;
 private final ImageEncoder encoder;
 private final Object lock=new Object();private boolean closed;private Job current;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"cefk-decision");t.setDaemon(true);return t;});
 private static final class Job {
  final String id;final byte[] chip,live;final Verification.Reply<Verification.Comparison> reply;
  final FaceClient.Cancellation cancellation=new FaceClient.Cancellation();
  Job(String id,byte[] chip,byte[] live,Verification.Reply<Verification.Comparison> reply){this.id=id;this.chip=chip.clone();this.live=live.clone();this.reply=reply;}
 }
 public CefkFaceAdapter(Verification.Mode mode,FaceClient client,Parameters parameters){
  this(mode,client,parameters,null);
 }
 public CefkFaceAdapter(Verification.Mode mode,FaceClient client,Parameters parameters,ImageEncoder encoder){
  if(mode==null)throw new IllegalArgumentException("Explicit mode required");this.mode=mode;this.client=client;this.parameters=parameters;this.encoder=encoder;
 }
 public Verification.Mode mode(){return mode;}
 public void compare(String id,byte[] chip,byte[] live,Verification.Reply<Verification.Comparison> reply){
  if(reply==null)throw new IllegalArgumentException("Reply required");Verification.Error reject=null;Job job=null;
  synchronized(lock){
   if(closed||client==null||parameters==null||!parameters.confirmed)reject=Verification.Error.NOT_CONNECTED;
   else if(current!=null)reject=Verification.Error.SDK_EXCEPTION;
   else if(id==null||id.isEmpty()||chip==null||live==null||chip.length==0||live.length==0||chip.length>8*1024*1024||live.length>8*1024*1024)reject=Verification.Error.INVALID_COMPARISON;
   else {job=new Job(id,chip,live,reply);current=job;final Job selected=job;worker.execute(()->execute(selected));}
  }
  if(reject!=null)reply.fail(reject);
 }
 private void execute(Job job){
  Verification.Comparison comparison=null;Verification.Error error=null;byte[] chip=null,live=null;
  try{
   chip=encoder==null?job.chip:encoder.encode(job.chip);live=encoder==null?job.live:encoder.encode(job.live);
   FaceClient.Scores scores=client.execute(FaceClient.Operation.FaceLiveAnd1V1,new byte[][]{chip,live},job.cancellation);
   if(scores.mode!=(mode==Verification.Mode.LIVE?FaceClient.Mode.REAL:FaceClient.Mode.SIMULATION))error=Verification.Error.WRONG_MODE;
   else if(scores.operation!=FaceClient.Operation.FaceLiveAnd1V1||scores.liveGrade==null||scores.faceGrade==null)error=Verification.Error.INVALID_COMPARISON;
   else comparison=new Verification.Comparison(matches(scores.liveGrade,parameters.liveThreshold,parameters.liveRule)?Verification.Liveness.PASSED:Verification.Liveness.FAILED,
     scores.faceGrade,parameters.faceThreshold,parameters.faceRule,"CEFK-FaceLiveAnd1V1-2.4.0");
  }catch(FaceClient.Failure failure){
   switch(failure.error){
    case UNAVAILABLE:error=Verification.Error.NOT_CONNECTED;break;
    case AUTH:error=Verification.Error.PERMISSION_DENIED;break;
    case TIMEOUT:error=Verification.Error.TIMEOUT;break;
    case CANCELLED:error=Verification.Error.CANCELLED;break;
    case PROTOCOL:case INVALID_INPUT:error=Verification.Error.INVALID_COMPARISON;break;
    default:error=Verification.Error.SDK_EXCEPTION;
   }
  }catch(Exception|LinkageError ex){error=Verification.Error.INVALID_COMPARISON;}
  finally{if(chip!=null)Arrays.fill(chip,(byte)0);if(live!=null)Arrays.fill(live,(byte)0);Arrays.fill(job.chip,(byte)0);Arrays.fill(job.live,(byte)0);}
  boolean deliver,release;
  synchronized(lock){deliver=!closed&&current==job&&!job.cancellation.isCancelled();if(current==job)current=null;release=closed;}
  if(release)client.close();
  if(deliver){if(error!=null)job.reply.fail(error);else job.reply.ok(comparison);}
 }
 private static boolean finite(double value){return Double.isFinite(value)&&value>=0&&value<=100;}
 private static boolean matches(double value,double threshold,Verification.ScoreRule rule){return rule==Verification.ScoreRule.AT_LEAST?value>=threshold:value<=threshold;}
 public void cancel(String id){synchronized(lock){if(current!=null&&current.id.equals(id))current.cancellation.cancel();}}
 public void close(){boolean release;synchronized(lock){if(closed)return;closed=true;release=current==null;if(current!=null)current.cancellation.cancel();worker.shutdown();}if(release&&client!=null)client.close();}
}
