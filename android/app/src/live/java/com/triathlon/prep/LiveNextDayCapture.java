package com.triathlon.prep;
import android.content.Context;import android.os.SystemClock;import android.view.Surface;
import java.io.IOException;import java.util.Arrays;import java.util.UUID;import java.util.concurrent.*;
/** Lazy LIVE camera: verified module -> configured buffers -> paired preview -> explicit still. */
final class LiveNextDayCapture implements NextDayCapture {
 private final Context app;private final NextDaySurfaceTarget surfaces;private LiveRuntime.Config config;
 private volatile Job current;private volatile Runnable changed=()->{};private boolean closed;
 private final ExecutorService images=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"nextday-png");t.setDaemon(true);return t;});
 private static final class Job {
  final String id;final long epoch,view;final PreviewReadiness readiness;final CountDownLatch delivered=new CountDownLatch(1),released=new CountDownLatch(1);
  PreviewLease<Surface> lease;CameraModule camera;boolean ended,shot;long started;volatile Frame frame;volatile IOException failure;
  Job(String id,long epoch,long view,long stable,long fresh){this.id=id;this.epoch=epoch;this.view=view;readiness=new PreviewReadiness(stable,fresh);}
 }
 LiveNextDayCapture(Context app,NextDaySurfaceTarget surfaces){this.app=app.getApplicationContext();this.surfaces=surfaces;}
 public void configure()throws IOException{config=Adapters.configuration(app);if(config==null||config.standardCamera==null||config.stageTimeoutMillis<1)throw new IOException("Trusted FACE camera configuration unavailable");}
 public int bufferWidth(){return config==null||config.standardCamera==null?0:config.standardCamera.previewWidth;}
 public int bufferHeight(){return config==null||config.standardCamera==null?0:config.standardCamera.previewHeight;}
 public void onChanged(Runnable callback){changed=callback==null?()->{}:callback;}
 public void preparePreview(String id,long epoch,long view)throws IOException{
  if(config==null)throw new IOException("FACE camera not configured");
  Job old=current;if(old!=null&&old.id.equals(id)&&old.epoch==epoch&&old.view==view)return;if(old!=null)stop(old,new IOException("FACE preview replaced"));
  Job job=new Job(id,epoch,view,config.previewStableMillis,config.previewFreshMillis);
  synchronized(this){if(closed)throw new IOException("FACE camera closed");current=job;}
  synchronized(job){
   job.lease=surfaces.lease(view);
   if(job.lease==null||!job.lease.borrowWithReason(reason->stop(job,new IOException("FACE Surface invalidated")))){stop(job,new IOException("FACE Surface unavailable"));throw job.failure;}
   try{
    Surface target=job.lease.value();if(target==null)throw new IOException("FACE Surface revoked");
    job.camera=Camera2Factory.create(app,target,Math.min(15000,config.stageTimeoutMillis),()->{job.lease.returned();job.released.countDown();changed.run();});
    surfaces.watch(view,timestamp->{job.readiness.surface(timestamp,SystemClock.elapsedRealtime());changed.run();});
    job.camera.start(id,config.standardCamera,new CameraModule.PreviewReply(){public void ready(CameraModule.PreviewInfo info){job.readiness.configured();changed.run();}public void frame(long timestamp){job.readiness.camera(timestamp,SystemClock.elapsedRealtime());changed.run();}public void fail(CameraModule.Error error){stop(job,new IOException("FACE preview failed"));}});
   }catch(IOException|RuntimeException|LinkageError error){stop(job,new IOException("FACE preview unavailable",error));throw new IOException("FACE preview unavailable",error);}
  }
 }
 public boolean previewReady(String id,long epoch,long view){Job job=current;return job!=null&&!job.ended&&job.id.equals(id)&&job.epoch==epoch&&job.view==view&&surfaces.revision()==view&&surfaces.ready()&&job.readiness.ready(SystemClock.elapsedRealtime());}
 public Frame capture(String id,long epoch,long view)throws IOException{
  Job job=current;if(!previewReady(id,epoch,view))throw new IOException("Current paired FACE preview required");
  synchronized(job){if(job.ended||job.shot)throw new IOException("FACE capture already consumed");job.shot=true;job.started=SystemClock.elapsedRealtime();
   job.camera.capture(id,new CameraModule.CaptureReply(){public void ok(CameraModule.Frame frame){try{images.execute(()->inspect(job,frame));}catch(RejectedExecutionException e){frame.close();stop(job,new IOException("FACE image owner closed"));}}public void fail(CameraModule.Error error){stop(job,new IOException("FACE capture failed"));}});
  }
  try{if(!job.delivered.await(16000,TimeUnit.MILLISECONDS))stop(job,new IOException("FACE capture deadline"));if(job.failure!=null)throw job.failure;Frame frame=job.frame;job.frame=null;if(frame==null)throw new IOException("FACE image unavailable");return frame;}
  catch(InterruptedException e){Thread.currentThread().interrupt();stop(job,new IOException("FACE capture interrupted",e));throw new IOException("FACE capture interrupted",e);}
 }
 private void inspect(Job job,CameraModule.Frame source){byte[] jpeg=null,png=null;StandardCameraCapture.Image image=null;Frame frame=null;
  try{jpeg=source.copyJpeg();image=new AndroidJpegInspector().inspect(jpeg,config.standardCamera,source.requestedJpegOrientationDegrees);if(image==null||image.width<1||image.height<1||source.sensorTimestamp<=0||image.faceCount!=1||!image.qualityReady)throw new IOException("Normalized current FACE PNG required");png=image.copy();frame=new Frame(job.id,job.epoch,UUID.randomUUID().toString(),job.started,job.view,ImageType.PNG,image.width,image.height,0,false,png);
   synchronized(job){if(job.ended)return;job.frame=frame;frame=null;}stop(job,null);
  }catch(Exception|LinkageError error){stop(job,new IOException("FACE image processing failed",error));}
  finally{if(frame!=null)frame.close();if(image!=null)image.close();if(jpeg!=null)Arrays.fill(jpeg,(byte)0);if(png!=null)Arrays.fill(png,(byte)0);source.close();}
 }
 private void stop(Job job,IOException failure){
  synchronized(job){if(job.ended)return;job.ended=true;job.failure=failure;job.readiness.invalidate();if(failure!=null&&job.frame!=null){job.frame.close();job.frame=null;}
   if(job.camera!=null)job.camera.close();if(job.lease!=null){job.lease.invalidate();if(job.camera==null){job.lease.returned();job.released.countDown();}}else job.released.countDown();job.delivered.countDown();}
  changed.run();
 }
 public void cancel(String id){Job job=current;if(job!=null&&job.id.equals(id))stop(job,new IOException("FACE operation cancelled"));}
 public void close()throws IOException{closed=true;Job job=current;if(job!=null){stop(job,new IOException("FACE camera closed"));try{if(!job.released.await(2000,TimeUnit.MILLISECONDS))throw new IOException("FACE camera release unconfirmed");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("FACE camera release interrupted",e);}}images.shutdown();}
}
