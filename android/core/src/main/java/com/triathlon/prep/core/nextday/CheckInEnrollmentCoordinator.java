package com.triathlon.prep.core.nextday;
import com.triathlon.prep.core.*;
import java.io.IOException;

/** Worker-owned post-save hook. Immediate cancellation clears the slot independently of this worker. */
public final class CheckInEnrollmentCoordinator {
 public enum State { IDLE, WAITING_FOR_SAVE, ENROLLED, NOT_ENROLLED }
 public interface Backend {FaceEnrollment forSaved(Verification.Snapshot saved)throws IOException;}
 public static final class Result {
  public final State state;public final String sessionId;public final FaceEnrollment.Reason reason;
  Result(State state,String sessionId,FaceEnrollment.Reason reason){this.state=state;this.sessionId=sessionId;this.reason=reason;}
 }
 private final QualifiedSceneSlot scenes;private final Backend backend;
 private String attempted="";private Result result=new Result(State.IDLE,"",FaceEnrollment.Reason.NONE);
 public CheckInEnrollmentCoordinator(QualifiedSceneSlot scenes,Backend backend){if(scenes==null||backend==null)throw new IllegalArgumentException("Explicit post-save ports required");this.scenes=scenes;this.backend=backend;}
 public Result update(Verification.Snapshot saved){
  scenes.expire();
  if(saved==null||saved.stage!=Verification.Stage.FINISHED||!CheckIn.trustedSuccess(saved.result)||saved.athlete==null){
   if(saved==null||saved.stage==Verification.Stage.IDLE||saved.stage==Verification.Stage.FINISHED)clear();
   else result=new Result(State.IDLE,"",FaceEnrollment.Reason.NONE);
   return result;
  }
  if(saved.saveState!=Verification.SaveState.SAVED)return result=new Result(State.WAITING_FOR_SAVE,saved.sessionId,FaceEnrollment.Reason.NOT_SAVED);
  if(attempted.equals(saved.sessionId))return result;
  attempted=saved.sessionId;return attempt(saved);
 }
 /** Caller validates the explicit action ticket; this additionally binds the original saved session. */
 public Result retry(Verification.Snapshot saved,String expectedSession){
  if(saved==null||expectedSession==null||!expectedSession.equals(saved.sessionId)||!expectedSession.equals(attempted)||saved.saveState!=Verification.SaveState.SAVED||!CheckIn.trustedSuccess(saved.result))return new Result(State.NOT_ENROLLED,"",FaceEnrollment.Reason.STALE_SCENE);
  return attempt(saved);
 }
 private Result attempt(Verification.Snapshot saved){
  try(CapturePort.Frame frame=scenes.frame(saved)){
   if(frame==null)return result=new Result(State.NOT_ENROLLED,saved.sessionId,FaceEnrollment.Reason.STALE_SCENE);
   FaceEnrollment enrollment=backend.forSaved(saved);
   if(enrollment==null)return result=new Result(State.NOT_ENROLLED,saved.sessionId,FaceEnrollment.Reason.ENGINE_NOT_READY);
   FaceEnrollment.Result done=enrollment.enroll(saved,frame);
   return result=new Result(done.state==FaceEnrollment.State.ENROLLED||done.state==FaceEnrollment.State.ALREADY_ENROLLED?State.ENROLLED:State.NOT_ENROLLED,saved.sessionId,done.reason);
  }catch(IOException|RuntimeException|LinkageError error){return result=new Result(State.NOT_ENROLLED,saved.sessionId,FaceEnrollment.Reason.SDK_OR_STORE_FAILED);}
 }
 public void clear(){scenes.clear();attempted="";result=new Result(State.IDLE,"",FaceEnrollment.Reason.NONE);}
 public Result result(){return result;}
}
