package com.triathlon.prep.nextday.host;
import com.triathlon.prep.core.Verification;import com.triathlon.prep.core.nextday.*;import com.triathlon.prep.nextday.storage.FaceLibraryTransfer;
import java.io.*;import java.util.UUID;import java.util.concurrent.atomic.AtomicBoolean;import java.util.function.Supplier;import javax.crypto.SecretKey;
/** Library file actions have their own bounded tickets, not biometric frame timestamps. */
public final class FaceLibraryTransferSession {
 /** Host validates the target engine against pinned parameters before its final locked commit. */
 public interface SourceCommit {void commit(FaceLibrary.Snapshot source,FaceEngine.Parameters pinned,FaceLibrary.MutationGuard lease)throws IOException;}
 public enum State { IDLE, CHOOSING, EXPORTING, EXPORTED, IMPORTING, REVIEW, IMPORTED, FAILED, CANCELLED }
 public static final class Offer {public final String id,keyCode;Offer(String id,String key){this.id=id;keyCode=key;}}
 private static final class Ticket {
  final String id=UUID.randomUUID().toString();final FaceLibrary.Snapshot original;final long epoch,deadline;final VerificationModeSession.Mode mode;final FaceEngine.Parameters parameters;final AtomicBoolean cancelled=new AtomicBoolean();final boolean exporting;
  SecretKey key;volatile FaceLibrary.Snapshot imported;
  Ticket(FaceLibrary.Snapshot original,VerificationModeSession.Snapshot authority,long deadline,FaceEngine.Parameters parameters,SecretKey key,boolean exporting){this.original=original;epoch=authority.epoch;mode=authority.currentMode;this.deadline=deadline;this.parameters=parameters;this.key=key;this.exporting=exporting;}
 }
 private final FaceLibrary library;private final VerificationModeSession authority;private final Verification.Clock clock;private final Supplier<FaceEngine.Parameters> parameters;private final FaceLibraryTransfer transfer=new FaceLibraryTransfer();
 private volatile Ticket active;private volatile State state=State.IDLE;
 private final SourceCommit sourceCommit;
 private synchronized void transition(Ticket ticket,State next){if(active==ticket)state=next;}
 public FaceLibraryTransferSession(FaceLibrary library,VerificationModeSession authority,Verification.Clock clock,Supplier<FaceEngine.Parameters> parameters){this(library,authority,clock,parameters,null);}
 /** Explicit source selection path; the default constructor retains exact batch matching. */
 public FaceLibraryTransferSession(FaceLibrary library,VerificationModeSession authority,Verification.Clock clock,Supplier<FaceEngine.Parameters> parameters,SourceCommit sourceCommit){if(library==null||authority==null||clock==null||parameters==null)throw new IllegalArgumentException("Explicit transfer ports required");this.library=library;this.authority=authority;this.clock=clock;this.parameters=parameters;this.sourceCommit=sourceCommit;}
 public State state(){return state;}
 public Offer beginExport()throws IOException{if(library.status()!=FaceLibrary.Status.READY||library.snapshot().entries.isEmpty())throw new IOException("Ready nonempty library required");SecretKey key=FaceLibraryTransfer.newKey();Ticket ticket=begin(key,true);return new Offer(ticket.id,FaceLibraryTransfer.encodeKey(key));}
 public Offer beginImport(String keyCode)throws IOException{Ticket ticket=begin(FaceLibraryTransfer.decodeKey(keyCode),false);return new Offer(ticket.id,"");}
 private synchronized Ticket begin(SecretKey key,boolean exporting)throws IOException{
  if(state==State.IMPORTING||state==State.EXPORTING)throw new IOException("File operation still running");
  VerificationModeSession.Snapshot mode=authority.snapshot();FaceEngine.Parameters p=parameters.get();
  if(mode.state!=VerificationModeSession.State.STABLE||p==null||!p.calibrated||(library.status()!=FaceLibrary.Status.EMPTY&&library.status()!=FaceLibrary.Status.READY))throw new IOException("Current library authority required");
  cancel();Ticket ticket=new Ticket(library.snapshot(),mode,clock.elapsedMillis()+120000,p,key,exporting);active=ticket;state=State.CHOOSING;return ticket;
 }
 private boolean current(Ticket ticket){
  if(!leaseCurrent(ticket))return false;
  FaceEngine.Parameters now=parameters.get();FaceLibrary.Snapshot snapshot=library.snapshot();
  return now!=null&&now.calibrated&&now.revision==ticket.parameters.revision&&Double.compare(now.matchThreshold,ticket.parameters.matchThreshold)==0&&Double.compare(now.minimumMargin,ticket.parameters.minimumMargin)==0&&now.maximumFrameAgeMillis==ticket.parameters.maximumFrameAgeMillis&&snapshot.scope.equals(ticket.original.scope)&&snapshot.revision.equals(ticket.original.revision);
 }
 private boolean leaseCurrent(Ticket ticket){return ticket!=null&&active==ticket&&!ticket.cancelled.get()&&clock.elapsedMillis()<=ticket.deadline&&authority.allows(ticket.mode,ticket.epoch);}
 private Ticket require(String id,boolean exporting)throws IOException{Ticket ticket=active;if(ticket==null||!ticket.id.equals(id)||ticket.exporting!=exporting||!current(ticket))throw new IOException("Library file action expired or revoked");return ticket;}
 private void check(Ticket ticket)throws IOException{if(!current(ticket))throw new IOException("Library file action expired or revoked");}
 public void exportTo(String id,OutputStream output)throws IOException{
  if(output==null)throw new IOException("Output required");Ticket ticket=require(id,true);if(state!=State.CHOOSING)throw new IOException("Export selection already consumed");transition(ticket,State.EXPORTING);
  try{OutputStream guarded=new FilterOutputStream(output){public void write(int value)throws IOException{check(ticket);out.write(value);}public void write(byte[] bytes,int start,int count)throws IOException{check(ticket);out.write(bytes,start,count);}public void flush()throws IOException{check(ticket);out.flush();}};
   transfer.exportReady(ticket.original,FaceLibrary.Status.READY,ticket.key,guarded);check(ticket);ticket.key=null;
  }catch(IOException|RuntimeException failure){transition(ticket,current(ticket)?State.FAILED:State.CANCELLED);ticket.key=null;throw failure;}
 }
 public void readImport(String id,InputStream input)throws IOException{
  if(input==null)throw new IOException("Input required");Ticket ticket=require(id,false);if(state!=State.CHOOSING)throw new IOException("Import selection already consumed");transition(ticket,State.IMPORTING);
  try{InputStream guarded=new FilterInputStream(input){public int read()throws IOException{check(ticket);return in.read();}public int read(byte[] bytes,int start,int count)throws IOException{check(ticket);return in.read(bytes,start,count);}};
   ticket.imported=(sourceCommit==null?transfer.preflight(guarded,ticket.key,ticket.original.scope):transfer.preflightSource(guarded,ticket.key,ticket.original.scope)).snapshot();check(ticket);ticket.key=null;
  }catch(IOException|RuntimeException failure){transition(ticket,current(ticket)?State.FAILED:State.CANCELLED);ticket.key=null;ticket.imported=null;throw failure;}
 }
 /** The stream owner acknowledges successful close, not merely authentication or flush. */
 public void exportClosed(String id)throws IOException{Ticket ticket=require(id,true);if(state!=State.EXPORTING)throw new IOException("Export IO not pending");transition(ticket,State.EXPORTED);}
 public void importClosed(String id)throws IOException{Ticket ticket=require(id,false);if(state!=State.IMPORTING||ticket.imported==null)throw new IOException("Import IO not pending");transition(ticket,State.REVIEW);}
 public synchronized void fileFailed(String id){Ticket ticket=active;if(ticket!=null&&ticket.id.equals(id)){ticket.cancelled.set(true);ticket.key=null;ticket.imported=null;active=null;state=State.FAILED;}}
 /** Sole SDK/Store owner calls this only after the staff confirmation ticket is current. */
 public void confirmImport(String id)throws IOException{
  Ticket ticket=require(id,false);if(state!=State.REVIEW||ticket.imported==null)throw new IOException("Explicit current preflight confirmation required");
  try{if(sourceCommit==null)library.importSnapshot(ticket.imported,()->current(ticket));else sourceCommit.commit(ticket.imported,ticket.parameters,()->leaseCurrent(ticket));transition(ticket,State.IMPORTED);ticket.imported=null;if(active==ticket)active=null;}
  catch(IOException|RuntimeException error){transition(ticket,current(ticket)?State.FAILED:State.CANCELLED);ticket.imported=null;if(active==ticket)active=null;throw error;}
 }
 public int candidateCount(){Ticket ticket=active;return ticket!=null&&state==State.REVIEW&&current(ticket)&&ticket.imported!=null?ticket.imported.entries.size():0;}
 public String candidateRevision(){Ticket ticket=active;return ticket!=null&&state==State.REVIEW&&current(ticket)&&ticket.imported!=null?ticket.imported.revision:"";}
 public FaceLibrary.Scope candidateScope(){Ticket ticket=active;return ticket!=null&&state==State.REVIEW&&current(ticket)&&ticket.imported!=null?ticket.imported.scope:null;}
 public synchronized void cancel(){Ticket ticket=active;if(ticket!=null){ticket.cancelled.set(true);ticket.key=null;ticket.imported=null;}active=null;state=State.CANCELLED;}
 public synchronized void cancel(String id){Ticket ticket=active;if(ticket!=null&&ticket.id.equals(id))cancel();}
}
