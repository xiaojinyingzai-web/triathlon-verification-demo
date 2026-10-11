package com.triathlon.prep;

import android.content.Context;
import android.view.Surface;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.cefk.hardware.CefkDeviceAdapter;
import java.util.concurrent.*;

/** Owns a passive UI target, a per-job camera, and total Surface/first-frame/still deadline. */
final class PreviewCameraCapture implements CefkDeviceAdapter.CapturePort {
 interface Cameras { CameraModule create(Surface target,Runnable released); }
 private final Cameras cameras;private final CameraModule.Config config;private final StandardCameraCapture.Inspector inspector;
 private final VerificationDisplay display;private final long timeout,stable,fresh;
 private final Object lock=new Object();private boolean closed;private Job current;
 private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"preview-target-deadline");t.setDaemon(true);return t;});
 private static final class Job {
  final String id;final Verification.Reply<Verification.Capture> reply;final PreviewReadiness gate;
  ScheduledFuture<?> deadline;PreviewLease<Surface> lease;StandardCameraCapture capture;
  boolean backendCreated;
  Job(String id,Verification.Reply<Verification.Capture> reply,long stable,long fresh){this.id=id;this.reply=reply;gate=new PreviewReadiness(stable,fresh);}
 }
 PreviewCameraCapture(Context app,CameraModule.Config config,VerificationDisplay display,long timeout,long stable,long fresh){
  this((surface,ack)->Camera2Factory.create(app,surface,timeout,ack),config,display,new AndroidJpegInspector(),timeout,stable,fresh);
 }
 PreviewCameraCapture(Cameras cameras,CameraModule.Config config,VerificationDisplay display,StandardCameraCapture.Inspector inspector,long timeout,long stable,long fresh){
  if(cameras==null||config==null||display==null||inspector==null||timeout<1||timeout>300000||stable<0||stable>=timeout)throw new IllegalArgumentException("Explicit preview configuration required");
  new PreviewReadiness(stable,fresh);this.cameras=cameras;this.config=config;this.display=display;this.inspector=inspector;this.timeout=timeout;this.stable=stable;this.fresh=fresh;
 }
 public Verification.Mode mode(){return Verification.Mode.LIVE;}
 public void capture(String id,Verification.Reply<Verification.Capture> reply){
  Job job;Verification.Error reject=null;
  synchronized(lock){
   if(closed)reject=Verification.Error.NOT_CONNECTED;else if(current!=null)reject=Verification.Error.DEVICE_ERROR;
   else if(id==null||id.trim().isEmpty())reject=Verification.Error.BAD_CAPTURE;
   if(reject==null){job=new Job(id,reply,stable,fresh);current=job;job.deadline=timer.schedule(()->finish(job,null,Verification.Error.TIMEOUT,true),timeout,TimeUnit.MILLISECONDS);}else job=null;
  }
  if(reject!=null){reply.fail(reject);return;}
  display.awaitPreview(id,lease->start(job,lease));
 }
 private void start(Job job,PreviewLease<Surface> lease){
  synchronized(lock){
   if(closed||current!=job)return;
   if(lease==null||!lease.borrowWithReason(intentional->finish(job,null,intentional?null:Verification.Error.DEVICE_ERROR,!intentional))){finish(job,null,Verification.Error.NOT_CONNECTED,true);return;}
   job.lease=lease;
   try{
    Surface surface=lease.value();if(surface==null){finish(job,null,Verification.Error.DEVICE_ERROR,true);return;}
    // The factory's Backend creation transfers return responsibility; before that cancellation returns directly.
    CameraModule module=cameras.create(surface,lease::returned);
    display.watchFrames(job.id,lease,timestamp->{job.gate.surface(timestamp,elapsed());});
    job.capture=new StandardCameraCapture(module,config,(bytes,c,r)->{
     StandardCameraCapture.Image image=inspector.inspect(bytes,c,r);byte[] copy=image==null?null:image.copy();
     try{synchronized(lock){if(current==job&&!closed)display.sceneEncoded(job.id,copy);}}finally{if(copy!=null)java.util.Arrays.fill(copy,(byte)0);}return image;
    },timeout,new StandardCameraCapture.Preview(){
     public void configured(CameraModule.PreviewInfo info){job.gate.configured();display.configurePreview(job.id,info.previewRotationDegrees,info.mirrorPreview);}
     public void frame(long timestamp){job.gate.camera(timestamp,elapsed());}
     private boolean shown;
     public boolean ready(long now){if(!shown&&job.gate.hasFrame()){shown=true;display.previewFrame(job.id);}return job.gate.ready(now);}
     public void finished(){job.gate.invalidate();}
    });
    // Starting may queue creation; the camera owner must acknowledge even cancellation before native open.
    job.backendCreated=true;
    job.capture.capture(job.id,new Verification.Reply<Verification.Capture>(){
     public void ok(Verification.Capture image){finish(job,image,null,true);}
     public void fail(Verification.Error error){finish(job,null,error,true);}
    });
   }catch(RuntimeException|LinkageError ex){finish(job,null,Verification.Error.DEVICE_ERROR,true);}
  }
 }
 private static long elapsed(){return System.nanoTime()/1000000L;}
 private void finish(Job job,Verification.Capture image,Verification.Error error,boolean deliver){
  synchronized(lock){if(current!=job)return;current=null;job.deadline.cancel(false);job.gate.invalidate();}
  if(job.capture!=null)job.capture.close();
  if(job.lease!=null){job.lease.invalidate();if(!job.backendCreated)job.lease.returned();}
  display.stopPreview(job.id);
  if(deliver){if(image!=null)job.reply.ok(image);else job.reply.fail(error);}
 }
 public void cancel(String id){Job job;synchronized(lock){job=current;if(job==null||!job.id.equals(id))return;}finish(job,null,null,false);}
 public void close(){Job job;synchronized(lock){if(closed)return;closed=true;job=current;}if(job!=null)finish(job,null,null,false);timer.shutdownNow();}
}
