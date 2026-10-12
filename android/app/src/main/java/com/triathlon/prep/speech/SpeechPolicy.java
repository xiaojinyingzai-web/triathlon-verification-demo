package com.triathlon.prep.speech;
import com.triathlon.prep.core.Verification.*;
import com.triathlon.prep.core.Verification.Error;
import com.triathlon.prep.core.Verification;
import java.util.*;
import com.triathlon.prep.speech.SpeechSettings.Scene;
public final class SpeechPolicy {
 public static final class Frame {
  public final String sessionId;public final Stage stage;public final Mode mode;public final Verdict verdict;public final Membership membership;public final Error error;public final SaveState save;public final boolean active,waiting;
  public Frame(String id,Stage stage,Mode mode,Verdict verdict,Membership membership,Error error,SaveState save,boolean active,boolean waiting){this.sessionId=id;this.stage=stage;this.mode=mode;this.verdict=verdict;this.membership=membership;this.error=error;this.save=save;this.active=active;this.waiting=waiting;}
  public static Frame from(Verification.Snapshot s,boolean active,boolean waiting){return s==null?new Frame("",Stage.IDLE,Mode.TEST,null,Membership.NO_ROSTER,Error.NONE,SaveState.NONE,false,false):new Frame(s.sessionId,s.stage,s.mode,s.result==null?null:s.result.verdict,s.membership,s.result==null?Error.NONE:s.result.error,s.saveState,active,waiting);}
 }
 private SpeechSettings settings;private final SpeechPort port;private final Runnable changed;
 private String session="";private boolean terminal,wasWaiting,active,closed;private long generation;
 private final Set<Scene> seen=EnumSet.noneOf(Scene.class);private SpeechPort.Progress progress=SpeechPort.Progress.IDLE;
 public SpeechPolicy(SpeechSettings settings,SpeechPort port,Runnable changed){if(settings==null||port==null||changed==null)throw new IllegalArgumentException("Dependencies required");this.settings=settings;this.port=port;this.changed=changed;}
 public synchronized void observe(Frame f){
  if(closed||f==null)return;
  if(!Objects.equals(session,f.sessionId)){cancel();session=f.sessionId;seen.clear();terminal=false;wasWaiting=false;}
  if(active&&!f.active)cancel();active=f.active;
  Scene scene=null;
  if(f.stage==Stage.FINISHED){if(!terminal)cancel();terminal=true;
   if(f.membership==Membership.NOT_LISTED&&f.error==Error.NOT_LISTED)scene=Scene.NOT_LISTED;
   else if(f.membership==Membership.MATCHED&&f.verdict==Verdict.PASSED&&f.save==SaveState.SAVED)scene=Scene.PASSED;
   else if(f.membership==Membership.MATCHED&&f.verdict==Verdict.REJECTED&&(f.error==Error.NO_MATCH||f.error==Error.LIVENESS_FAILED))scene=Scene.REJECTED;
  }else if(!terminal&&f.stage==Stage.CAPTURING)scene=Scene.FACE_CAMERA;
  boolean waiting=f.active&&f.waiting&&f.stage==Stage.IDLE;
  if(waiting&&!wasWaiting)scene=Scene.WAIT_CARD;wasWaiting=waiting;
  if(scene==null||!seen.add(scene))return;
  // Consume even disabled/not ready: engine recovery and setting changes cannot replay an old event.
  if(!f.active||!settings.enabled)return;
  submit(settings.text(scene),settings.volumePercent,f.mode==Mode.TEST);
 }
 public synchronized void settings(SpeechSettings next){if(next==null)throw new IllegalArgumentException("Settings required");if(closed)return;settings=next;if(!next.enabled||next.volumePercent==0)cancel();}
 public synchronized SpeechSettings settings(){return settings;}
 public synchronized void preview(Scene scene,String draft,int volume,boolean test,boolean visible){if(closed||!visible)return;if(scene==null||volume<0||volume>100)throw new IllegalArgumentException("Preview parameters invalid");String text=SpeechSettings.normalize(draft);submit(text.isEmpty()?SpeechSettings.defaultText(scene):text,volume,test);}
 private void submit(String text,int volume,boolean test){
  cancel();if(volume==0){progress=SpeechPort.Progress.MUTED;notifyChanged();return;}
  if(port.readiness()!=SpeechPort.Readiness.READY){progress=SpeechPort.Progress.FAILED;notifyChanged();return;}
  final long expected=++generation;progress=SpeechPort.Progress.QUEUED;
  try {boolean queued=port.speak(new SpeechPort.Request("speech-"+expected,(test?"模拟测试，":"")+text,volume/100f),value->{synchronized(SpeechPolicy.this){if(closed||generation!=expected)return;progress=value;notifyChanged();}});if(!queued&&generation==expected)progress=SpeechPort.Progress.FAILED;}
  catch(RuntimeException ex){if(generation==expected)progress=SpeechPort.Progress.FAILED;}
  notifyChanged();
 }
 public synchronized void cancel(){generation++;try{port.stop();}catch(RuntimeException ignored){}progress=SpeechPort.Progress.CANCELLED;}
 public synchronized SpeechPort.Progress progress(){return progress;}
 public synchronized void close(){if(closed)return;cancel();closed=true;try{port.close();}catch(RuntimeException ignored){}}
 private void notifyChanged(){try{changed.run();}catch(RuntimeException ignored){}}
}
