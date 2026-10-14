package com.triathlon.prep.core.nextday;
import com.triathlon.prep.core.Verification;
import java.util.Arrays;

/** One bounded current-scene lease. No photos in records, UI, imports or persistent storage. */
public final class QualifiedSceneSlot implements Verification.QualifiedCaptureObserver,AutoCloseable {
 private final Verification.Mode mode;private final VerificationModeSession authority;private final Verification.Clock clock;private final long maximumAgeMillis;
 private Verification.Snapshot origin;private Verification.SceneMetadata provenance;private byte[] photo;private long epoch;
 private CapturePort.Frame issuedFrame;
 public QualifiedSceneSlot(Verification.Mode mode,VerificationModeSession authority,Verification.Clock clock,long maximumAgeMillis){
  if(mode==null||authority==null||clock==null||maximumAgeMillis<1||maximumAgeMillis>60000)throw new IllegalArgumentException("Bounded scene authority required");
  this.mode=mode;this.authority=authority;this.clock=clock;this.maximumAgeMillis=maximumAgeMillis;
 }
 public synchronized void captured(Verification.Snapshot context,byte[] scene,Verification.SceneMetadata metadata,long observedAtElapsed){
  clear();VerificationModeSession.Snapshot selection=authority.snapshot();
  if(context==null||context.mode!=mode||context.membership!=Verification.Membership.MATCHED||context.stage!=Verification.Stage.ANALYZING||metadata==null||scene==null||scene.length<1||scene.length>8*1024*1024||!authority.allows(VerificationModeSession.Mode.CHECKIN,selection.epoch)||metadata.requestStartedElapsed>observedAtElapsed||observedAtElapsed-metadata.requestStartedElapsed>maximumAgeMillis)return;
  if(metadata.encoding==Verification.SceneMetadata.Encoding.SYNTHETIC&&mode!=Verification.Mode.TEST)return;
  if(metadata.encoding==Verification.SceneMetadata.Encoding.PNG&&!pngMatches(scene,metadata))return;
  origin=context;provenance=metadata;epoch=selection.epoch;photo=scene.clone();
 }
 public synchronized boolean current(Verification.Snapshot saved,CapturePort.Frame frame){
  return eligible(saved)&&frame!=null&&frame==issuedFrame&&!frame.closed()&&frame.epoch==epoch&&frame.operationId.equals(origin.sessionId)&&frame.frameId.equals(provenance.frameId)&&frame.capturedAtElapsed==provenance.requestStartedElapsed;
 }
 public synchronized CapturePort.Frame frame(Verification.Snapshot saved){
  if(!eligible(saved)){clear();return null;}
  // TEST's opaque marker is never a real image; only the isolated TEST engine may consume it.
  CapturePort.ImageType type=provenance.encoding==Verification.SceneMetadata.Encoding.PNG?CapturePort.ImageType.PNG:CapturePort.ImageType.JPEG;
  if(issuedFrame==null||issuedFrame.closed())issuedFrame=new CapturePort.Frame(origin.sessionId,epoch,provenance.frameId,provenance.requestStartedElapsed,0,type,provenance.width,provenance.height,0,false,photo);
  return issuedFrame;
 }
 private boolean eligible(Verification.Snapshot saved){
  long now=clock.elapsedMillis();return photo!=null&&origin!=null&&provenance!=null&&saved!=null&&saved.mode==mode&&saved.sessionId.equals(origin.sessionId)&&saved.rosterBatchId.equals(origin.rosterBatchId)&&authority.allows(VerificationModeSession.Mode.CHECKIN,epoch)&&now>=provenance.requestStartedElapsed&&now-provenance.requestStartedElapsed<=maximumAgeMillis;
 }
 public synchronized void expire(){if(photo!=null&&(clock.elapsedMillis()<provenance.requestStartedElapsed||clock.elapsedMillis()-provenance.requestStartedElapsed>maximumAgeMillis||!authority.allows(VerificationModeSession.Mode.CHECKIN,epoch)))clear();}
 public synchronized void clear(){if(issuedFrame!=null)issuedFrame.close();issuedFrame=null;if(photo!=null)Arrays.fill(photo,(byte)0);photo=null;origin=null;provenance=null;epoch=0;}
 public void close(){clear();}
 private static boolean pngMatches(byte[] bytes,Verification.SceneMetadata metadata){
  if(bytes.length<24||metadata.sensorTimestampNanos<=0||metadata.rotation!=0||metadata.mirror)return false;
  byte[] magic={(byte)137,80,78,71,13,10,26,10};for(int i=0;i<magic.length;i++)if(bytes[i]!=magic[i])return false;
  if(bytes[12]!='I'||bytes[13]!='H'||bytes[14]!='D'||bytes[15]!='R')return false;
  return integer(bytes,16)==metadata.width&&integer(bytes,20)==metadata.height;
 }
 private static int integer(byte[] data,int offset){return (data[offset]&255)<<24|(data[offset+1]&255)<<16|(data[offset+2]&255)<<8|(data[offset+3]&255);}
}
