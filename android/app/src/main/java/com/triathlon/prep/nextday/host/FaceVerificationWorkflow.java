package com.triathlon.prep.nextday.host;
import com.triathlon.prep.core.*;import com.triathlon.prep.core.nextday.*;import com.triathlon.prep.core.nextday.decision.FaceDecision;import com.triathlon.prep.nextday.records.*;
import java.io.IOException;import java.util.*;import java.util.function.LongSupplier;
/** One Host worker owns all ports; operation authority remains independently revocable. */
public final class FaceVerificationWorkflow {
 public enum State { BLOCKED, COMPLETE, CANCELLED, FAILED, SAVE_FAILED }
 public static final class Result {
  public final State state;public final String operationId;public final FaceDecision.Result decision;
  public final FaceEngine.StepResult quality,liveness;public final List<FaceEngine.Candidate> candidates;
  Result(State state,String id,FaceDecision.Result decision,FaceEngine.StepResult quality,FaceEngine.StepResult liveness,List<FaceEngine.Candidate> candidates){this.state=state;operationId=id;this.decision=decision;this.quality=quality;this.liveness=liveness;this.candidates=Collections.unmodifiableList(new ArrayList<>(candidates));}
 }
 private final VerificationModeSession authority;private final CapturePort capture;private final FaceEngine engine;private final FaceLibrary library;private final NextDayRecordStore store;private final Verification.Clock clock;private final FaceEngine.RuntimeGate gate;private final LongSupplier surface;
 private Ticket pending;
 private static final class Ticket {
  final NextDayRecord record;final Result result;final long epoch,view,captured;final FaceEngine.Parameters parameters;
  Ticket(NextDayRecord record,Result result,long epoch,long view,long captured,FaceEngine.Parameters parameters){this.record=record;this.result=result;this.epoch=epoch;this.view=view;this.captured=captured;this.parameters=parameters;}
 }
 public FaceVerificationWorkflow(VerificationModeSession authority,CapturePort capture,FaceEngine engine,FaceLibrary library,NextDayRecordStore store,Verification.Clock clock,FaceEngine.RuntimeGate gate,LongSupplier surface){
  if(authority==null||capture==null||engine==null||library==null||store==null||clock==null||gate==null||surface==null)throw new IllegalArgumentException("Explicit FACE ports required");this.authority=authority;this.capture=capture;this.engine=engine;this.library=library;this.store=store;this.clock=clock;this.gate=gate;this.surface=surface;
 }
 public Result run(String operationId){
  if(operationId==null||!UUID.fromString(operationId).toString().equals(operationId))throw new IllegalArgumentException("Canonical operation UUID required");
  pending=null;long epoch=authority.snapshot().epoch,view=surface.getAsLong();FaceLibrary.Snapshot pinned=library.snapshot();FaceEngine.Parameters parameters=engine.parameters();
  if(parameters==null||!current(epoch,view,clock.elapsedMillis(),pinned.revision,parameters)||pinned.entries.isEmpty())return empty(State.BLOCKED,operationId);
  byte[] feature=null;
  try(CapturePort.Frame frame=capture.capture(operationId,epoch,view)){
   if(frame==null||frame.closed()||!frame.operationId.equals(operationId)||frame.epoch!=epoch||frame.surfaceRevision!=view||!current(epoch,view,frame.capturedAtElapsed,pinned.revision,parameters))return empty(State.CANCELLED,operationId);
   try(FaceEngine.Evaluation evaluation=engine.evaluate(frame,true)){
    if(evaluation==null)return empty(State.FAILED,operationId);
    if(!current(epoch,view,frame.capturedAtElapsed,pinned.revision,parameters))return empty(State.CANCELLED,operationId);
    List<FaceEngine.Candidate> candidates=Collections.emptyList();
    if(evaluation.quality==FaceEngine.StepResult.PASSED&&evaluation.liveness==FaceEngine.StepResult.PASSED){feature=evaluation.feature();if(feature==null)return empty(State.FAILED,operationId);candidates=engine.search(feature,2);}
    if(!current(epoch,view,frame.capturedAtElapsed,pinned.revision,parameters))return empty(State.CANCELLED,operationId);
    FaceDecision.Result decision=FaceDecision.decide(new FaceDecision.Input(pinned.scope,library.status(),library.snapshot(),pinned.revision,engine.readiness(),gate.licenceVerified(),parameters,engine.parameters(),evaluation.quality,evaluation.liveness,candidates));
    NextDayRecord.Outcome outcome=decision.passed()?NextDayRecord.Outcome.PASSED:decision.reason==FaceDecision.Reason.NO_CANDIDATES||decision.reason==FaceDecision.Reason.LOW_SCORE?NextDayRecord.Outcome.REJECTED:NextDayRecord.Outcome.MANUAL_REQUIRED;
    Result result=new Result(State.COMPLETE,operationId,decision,evaluation.quality,evaluation.liveness,candidates);
    pending=new Ticket(new NextDayRecord(operationId,pinned.scope.mode,pinned.scope.batchId,pinned.revision,parameters.revision,decision.person==null?null:decision.person.id,outcome,clock.wallMillis()),result,epoch,view,frame.capturedAtElapsed,parameters);
    return save();
   }
  }catch(IOException|RuntimeException|LinkageError error){return empty(authority.allows(VerificationModeSession.Mode.FACE,epoch)?State.FAILED:State.CANCELLED,operationId);}
  finally{if(feature!=null)Arrays.fill(feature,(byte)0);}
 }
 public Result retrySave(String operationId){if(pending==null||!pending.record.operationId.equals(operationId))return empty(State.CANCELLED,operationId);return save();}
 private Result save(){
  Ticket ticket=pending;
  synchronized(authority){
   if(ticket==null||!current(ticket.epoch,ticket.view,ticket.captured,ticket.record.libraryRevision,ticket.parameters)){pending=null;return empty(State.CANCELLED,ticket==null?"":ticket.record.operationId);}
   try{store.save(ticket.record);pending=null;return ticket.result;}
   catch(IOException|RuntimeException error){return new Result(State.SAVE_FAILED,ticket.record.operationId,null,ticket.result.quality,ticket.result.liveness,Collections.emptyList());}
  }
 }
 private boolean current(long epoch,long view,long captured,String revision,FaceEngine.Parameters pinned){
  FaceEngine.Parameters now=engine.parameters();long elapsed=clock.elapsedMillis();FaceLibrary.Snapshot active=library.snapshot();
  return authority.allows(VerificationModeSession.Mode.FACE,epoch)&&surface.getAsLong()==view&&elapsed>=captured&&elapsed-captured<=pinned.maximumFrameAgeMillis&&gate.nativeInitializationAllowed()&&gate.licenceVerified()&&engine.readiness()==FaceEngine.Readiness.READY&&engine.mode()==active.scope.mode&&engine.scope().equals(active.scope)&&library.status()==FaceLibrary.Status.READY&&active.revision.equals(revision)&&now!=null&&now.calibrated&&pinned.calibrated&&pinned.revision==now.revision&&Double.compare(pinned.matchThreshold,now.matchThreshold)==0&&Double.compare(pinned.minimumMargin,now.minimumMargin)==0&&pinned.maximumFrameAgeMillis==now.maximumFrameAgeMillis;
 }
 public void cancel(String operationId){pending=null;capture.cancel(operationId);}
 private static Result empty(State state,String id){return new Result(state,id,null,FaceEngine.StepResult.UNKNOWN,FaceEngine.StepResult.UNKNOWN,Collections.emptyList());}
}
