package com.triathlon.prep.core.nextday;
import com.triathlon.prep.core.*;
import java.io.IOException;import java.util.Arrays;

/** Post-SAVED feature registration on the sole SDK/library owner queue. Never rewrites check-in. */
public final class FaceEnrollment {
 public enum State { ENROLLED, ALREADY_ENROLLED, REJECTED, CANCELLED, FAILED }
 public enum Reason { NONE, NOT_SAVED, UNTRUSTED_CHECKIN, STALE_SCENE, SCOPE_MISMATCH, ENGINE_NOT_READY, PARAMETERS_UNVERIFIED, PARAMETERS_CHANGED, QUALITY_FAILED, FEATURE_MISSING, LIBRARY_NOT_READY, ID_EXHAUSTED, SDK_OR_STORE_FAILED }
 public static final class Result {public final State state;public final Reason reason;public final String bib;Result(State state,Reason reason,String bib){this.state=state;this.reason=reason;this.bib=bib;}}
 private final FaceLibrary library;private final FaceEngine engine;private final QualifiedSceneSlot scenes;private final Verification.Clock clock;
 private String committedFrame="";
 public FaceEnrollment(FaceLibrary library,FaceEngine engine,QualifiedSceneSlot scenes,Verification.Clock clock){if(library==null||engine==null||scenes==null||clock==null)throw new IllegalArgumentException("Explicit enrollment ports required");this.library=library;this.engine=engine;this.scenes=scenes;this.clock=clock;}
 public Result enroll(Verification.Snapshot saved,CapturePort.Frame frame){
  if(saved==null||saved.saveState!=Verification.SaveState.SAVED)return rejected(Reason.NOT_SAVED);
  if(!CheckIn.trustedSuccess(saved.result)||saved.athlete==null)return rejected(Reason.UNTRUSTED_CHECKIN);
  if(!scenes.current(saved,frame))return new Result(State.CANCELLED,Reason.STALE_SCENE,"");
  FaceLibrary.Snapshot snapshot=library.snapshot();
  if(saved.mode!=engine.mode()||snapshot.scope.mode!=saved.mode||!snapshot.scope.equals(engine.scope())||!snapshot.scope.batchId.equals(saved.rosterBatchId))return rejected(Reason.SCOPE_MISMATCH);
  if(engine.readiness()!=FaceEngine.Readiness.READY)return rejected(Reason.ENGINE_NOT_READY);
  FaceEngine.Parameters before=engine.parameters();if(before==null||!before.calibrated)return rejected(Reason.PARAMETERS_UNVERIFIED);
  if(library.status()!=FaceLibrary.Status.READY&&library.status()!=FaceLibrary.Status.EMPTY)return rejected(Reason.LIBRARY_NOT_READY);
  String key=saved.sessionId+"/"+frame.frameId;if(key.equals(committedFrame))return new Result(State.ALREADY_ENROLLED,Reason.NONE,saved.athlete.bib);
  byte[] feature=null;
  try(FaceEngine.Evaluation evaluation=engine.evaluate(frame,false)){
   if(evaluation==null||evaluation.quality!=FaceEngine.StepResult.PASSED)return rejected(Reason.QUALITY_FAILED);
   feature=evaluation.feature();if(feature==null)return rejected(Reason.FEATURE_MISSING);
   if(!scenes.current(saved,frame))return new Result(State.CANCELLED,Reason.STALE_SCENE,"");
   if(!same(before,engine.parameters())||engine.readiness()!=FaceEngine.Readiness.READY)return rejected(Reason.PARAMETERS_CHANGED);
   int id=0,largest=0;for(FaceLibrary.Entry entry:snapshot.entries){if(entry.bib.equals(saved.athlete.bib))id=entry.id;largest=Math.max(largest,entry.id);}
   if(id==0){if(largest==Integer.MAX_VALUE)return rejected(Reason.ID_EXHAUSTED);id=largest+1;}
   library.upsert(new FaceLibrary.Entry(id,saved.athlete.bib,saved.athlete.name,saved.athlete.group,feature,clock.wallMillis()),
    ()->scenes.current(saved,frame)&&same(before,engine.parameters())&&engine.readiness()==FaceEngine.Readiness.READY);
   committedFrame=key;return new Result(State.ENROLLED,Reason.NONE,saved.athlete.bib);
  }catch(IOException|RuntimeException|LinkageError error){return scenes.current(saved,frame)?new Result(State.FAILED,Reason.SDK_OR_STORE_FAILED,""):new Result(State.CANCELLED,Reason.STALE_SCENE,"");}
  finally{if(feature!=null)Arrays.fill(feature,(byte)0);}
 }
 private static Result rejected(Reason reason){return new Result(State.REJECTED,reason,"");}
 private static boolean same(FaceEngine.Parameters a,FaceEngine.Parameters b){return b!=null&&a.revision==b.revision&&a.calibrated==b.calibrated&&a.matchThreshold==b.matchThreshold&&a.minimumMargin==b.minimumMargin&&a.maximumFrameAgeMillis==b.maximumFrameAgeMillis;}
}
