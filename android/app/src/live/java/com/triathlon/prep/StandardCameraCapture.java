package com.triathlon.prep;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.cefk.hardware.CefkDeviceAdapter;
import java.util.Arrays;
import java.util.concurrent.*;
import android.os.SystemClock;
/** One memory-only Camera2 capture per verification session; never produces identity/liveness scores. */
public final class StandardCameraCapture implements CefkDeviceAdapter.CapturePort {
 public interface Inspector { Image inspect(byte[] jpeg,CameraModule.Config config,int requestedRotation)throws Exception; }
 public interface Preview {
  void configured(CameraModule.PreviewInfo info);
  void frame(long sensorTimestamp);
  boolean ready(long elapsedMillis);
  void finished();
 }
 public static final class Image implements AutoCloseable{
  public final int faceCount,width,height;public final boolean qualityReady;private byte[] png;
  public Image(byte[] png,int faceCount,boolean qualityReady){this(png,faceCount,qualityReady,0,0);}
  public Image(byte[] png,int faceCount,boolean qualityReady,int width,int height){
   if(!((width==0&&height==0)||(width>0&&height>0&&width<=4096&&height<=4096&&(long)width*height<=4_000_000)))throw new IllegalArgumentException("Normalized image dimensions invalid");
   this.png=png==null?null:png.clone();this.faceCount=faceCount;this.qualityReady=qualityReady;this.width=width;this.height=height;
  }
  public synchronized byte[] copy(){return png==null?null:png.clone();}
  public synchronized void close(){if(png!=null){Arrays.fill(png,(byte)0);png=null;}}
 }
 private final CameraModule camera;private final CameraModule.Config config;private final Inspector inspector;private final long timeoutMillis;private final Preview preview;
 private final Object lock=new Object();private Job current;private boolean closed;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"standard-camera-image");t.setDaemon(true);return t;});
 private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"standard-camera-deadline");t.setDaemon(true);return t;});
 private static final class Job{
  final String id;final String frameId=java.util.UUID.randomUUID().toString();final Verification.Reply<Verification.Capture> reply;ScheduledFuture<?> deadline,previewWait;
  long requestStartedElapsed;boolean shot;
  Job(String id,Verification.Reply<Verification.Capture> reply){this.id=id;this.reply=reply;}
 }
 public StandardCameraCapture(CameraModule camera,CameraModule.Config config,Inspector inspector,long timeoutMillis){
  this(camera,config,inspector,timeoutMillis,null);
 }
 public StandardCameraCapture(CameraModule camera,CameraModule.Config config,Inspector inspector,long timeoutMillis,Preview preview){
  if(camera==null||timeoutMillis<1||timeoutMillis>300000)throw new IllegalArgumentException("Owned camera and timeout required");
  this.camera=camera;this.config=config;this.inspector=inspector;this.timeoutMillis=timeoutMillis;this.preview=preview;
 }
 public Verification.Mode mode(){return Verification.Mode.LIVE;}
 public void capture(String id,Verification.Reply<Verification.Capture> reply){
  if(reply==null)throw new IllegalArgumentException("Reply required");
  Job job=null;Verification.Error reject=null;
  synchronized(lock){
   if(closed||config==null||inspector==null)reject=Verification.Error.NOT_CONNECTED;
   else if(id==null||id.trim().isEmpty())reject=Verification.Error.BAD_CAPTURE;
   else if(current!=null)reject=Verification.Error.DEVICE_ERROR;
   else{job=new Job(id,reply);current=job;final Job selected=job;job.deadline=timer.schedule(()->finish(selected,null,Verification.Error.TIMEOUT),timeoutMillis,TimeUnit.MILLISECONDS);}
  }
  if(reject!=null){reply.fail(reject);return;}
  final Job selected=job;
  try{camera.start(id,config,new CameraModule.PreviewReply(){
   public void ready(CameraModule.PreviewInfo info){
    if(!active(selected))return;
    if(preview==null){shoot(selected);return;}
    preview.configured(info);
    synchronized(lock){if(current==selected&&!closed)selected.previewWait=timer.scheduleWithFixedDelay(()->{
     if(!active(selected))return;
     if(preview.ready(System.nanoTime()/1000000L)){
      synchronized(lock){if(current!=selected||closed)return;if(selected.previewWait!=null)selected.previewWait.cancel(false);}
      shoot(selected);
     }
    },25,25,TimeUnit.MILLISECONDS);}
   }
   public void frame(long timestamp){if(active(selected)&&preview!=null)preview.frame(timestamp);}
   public void fail(CameraModule.Error error){finish(selected,null,map(error));}
  });}catch(RuntimeException|LinkageError ex){finish(selected,null,Verification.Error.SDK_EXCEPTION);}
 }
 private void shoot(Job selected){
    synchronized(lock){if(closed||current!=selected||selected.shot)return;selected.shot=true;selected.requestStartedElapsed=SystemClock.elapsedRealtime();}
    camera.capture(selected.id,new CameraModule.CaptureReply(){
     public void ok(CameraModule.Frame frame){
      if(!active(selected)){frame.close();return;}
      try{worker.execute(()->inspect(selected,frame));}catch(RejectedExecutionException ex){frame.close();finish(selected,null,Verification.Error.NOT_CONNECTED);}
     }
     public void fail(CameraModule.Error error){finish(selected,null,map(error));}
    });
 }
 private boolean active(Job job){synchronized(lock){return !closed&&current==job;}}
 private void inspect(Job job,CameraModule.Frame frame){
  byte[] jpeg=null,png=null;Image image=null;
  try{
   if(!active(job)||!job.id.equals(frame.sessionId))return;
   jpeg=frame.copyJpeg();image=inspector.inspect(jpeg,config,frame.requestedJpegOrientationDegrees);
   png=image==null?null:image.copy();
   if(png==null||png.length<8||png.length>8*1024*1024||!Arrays.equals(Arrays.copyOf(png,8),new byte[]{(byte)137,80,78,71,13,10,26,10})
     ||image.faceCount<0||image.faceCount>2)throw new CameraModule.Failure(CameraModule.Error.INVALID_IMAGE);
   Verification.SceneMetadata provenance=image.width==0?null:new Verification.SceneMetadata(job.frameId,job.requestStartedElapsed,frame.sensorTimestamp,
    image.width,image.height,0,false,Verification.SceneMetadata.Encoding.PNG);
   finish(job,new Verification.Capture(png,image.faceCount,image.qualityReady,provenance),null);
  }catch(CameraModule.Failure ex){finish(job,null,map(ex.error));}
  catch(Exception|LinkageError ex){finish(job,null,Verification.Error.BAD_CAPTURE);}
  catch(OutOfMemoryError ex){finish(job,null,Verification.Error.BAD_CAPTURE);}
  finally{if(jpeg!=null)Arrays.fill(jpeg,(byte)0);if(png!=null)Arrays.fill(png,(byte)0);if(image!=null)image.close();frame.close();}
 }
 private void finish(Job job,Verification.Capture capture,Verification.Error error){
  boolean deliver;
  synchronized(lock){deliver=!closed&&current==job;if(deliver){current=null;job.deadline.cancel(false);if(job.previewWait!=null)job.previewWait.cancel(false);}}
  if(!deliver){if(capture!=null)capture.close();return;}
  camera.cancel(job.id);
  if(preview!=null)preview.finished();
  try{if(capture!=null)job.reply.ok(capture);else job.reply.fail(error==null?Verification.Error.BAD_CAPTURE:error);}
  finally{if(capture!=null)capture.close();}
 }
 private static Verification.Error map(CameraModule.Error error){
  if(error==CameraModule.Error.PERMISSION_DENIED)return Verification.Error.PERMISSION_DENIED;
  if(error==CameraModule.Error.TIMEOUT)return Verification.Error.TIMEOUT;
  if(error==CameraModule.Error.NOT_CONFIGURED||error==CameraModule.Error.NO_CAMERA||error==CameraModule.Error.CLOSED)return Verification.Error.NOT_CONNECTED;
  if(error==CameraModule.Error.INVALID_IMAGE||error==CameraModule.Error.INVALID_CONFIG)return Verification.Error.BAD_CAPTURE;
  return Verification.Error.DEVICE_ERROR;
 }
 public void cancel(String id){
  synchronized(lock){if(current==null||!current.id.equals(id))return;current.deadline.cancel(false);if(current.previewWait!=null)current.previewWait.cancel(false);current=null;}
  camera.cancel(id);
  if(preview!=null)preview.finished();
 }
 public void close(){
  synchronized(lock){if(closed)return;closed=true;if(current!=null){current.deadline.cancel(false);if(current.previewWait!=null)current.previewWait.cancel(false);current=null;}}
  if(preview!=null)preview.finished();
  camera.close();worker.shutdown();timer.shutdownNow();
 }
}
