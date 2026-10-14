package com.triathlon.prep.core.nextday;
import java.util.UUID;

/** Mode changes are explicit single-use staff requests; no card/clock/face event can choose a mode. */
public final class VerificationModeSession {
 public enum Mode { CHECKIN, FACE }
 public enum State { STABLE, SWITCHING, FAILED }
 public interface Reply {void released(boolean success);}
 public interface Resources {void stop(Mode current,Mode target,long epoch,Reply reply);}
 public static final class Snapshot {
  public final Mode currentMode,targetMode;public final State state;public final String requestToken;public final long revision,epoch;
  Snapshot(Mode current,Mode target,State state,String token,long revision,long epoch){this.currentMode=current;this.targetMode=target;this.state=state;this.requestToken=token;this.revision=revision;this.epoch=epoch;}
 }
 private final Resources resources;private Mode current=Mode.CHECKIN,target=Mode.CHECKIN;private State state=State.STABLE;private String token=UUID.randomUUID().toString();private long revision=1,epoch=1,pendingHandoff;
 public VerificationModeSession(Resources resources){if(resources==null)throw new IllegalArgumentException("Explicit resource handoff required");this.resources=resources;}
 public synchronized Snapshot snapshot(){return new Snapshot(current,target,state,token,revision,epoch);}
 public synchronized boolean allows(Mode offered,long operationEpoch){return state==State.STABLE&&current==offered&&epoch==operationEpoch;}
 public boolean request(Mode requested,String offeredToken,long offeredRevision){
  final long handoff;final Mode previous;
  synchronized(this){if(requested==null||state==State.SWITCHING||token.isEmpty()||!token.equals(offeredToken)||revision!=offeredRevision||state==State.STABLE&&requested==current)return false;previous=current;target=requested;state=State.SWITCHING;token="";revision++;handoff=++epoch;pendingHandoff=handoff;}
  try{resources.stop(previous,requested,handoff,success->released(handoff,success));}catch(RuntimeException|LinkageError error){released(handoff,false);}return true;
 }
 private synchronized void released(long offeredEpoch,boolean success){if(state!=State.SWITCHING||pendingHandoff!=offeredEpoch)return;if(success){current=target;state=State.STABLE;}else state=State.FAILED;revision++;token=UUID.randomUUID().toString();}
 /** Page/background/Surface changes revoke operation epochs without selecting a different mode. */
 public synchronized void invalidateOperations(){epoch++;revision++;if(state!=State.SWITCHING)token=UUID.randomUUID().toString();}
}
