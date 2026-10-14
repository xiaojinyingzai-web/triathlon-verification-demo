package com.triathlon.prep;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import com.triathlon.prep.core.Verification;
import java.util.Arrays;
import java.util.UUID;

/** One explicit operator shot through the unchanged production capture/inspection chain. Memory only. */
public final class DebugSingleCapture implements AutoCloseable {
 public interface Listener {
  void status(DebugPrivatePreview.Status status);
  void configured(CameraModule.PreviewInfo info);
  void shotReady(boolean ready);
  void captured(Result result);
 }
 /** Borrowed synchronously on main thread. copyPng is for temporary UI decode only; caller wipes it. */
 public static final class Result implements AutoCloseable {
  public final int width,height,jpegBytes;public final long sensorTimestampNanos;
  private byte[] png;
  Result(byte[] png,Verification.SceneMetadata meta,int jpegBytes){
   this.png=png;this.width=meta.width;this.height=meta.height;this.sensorTimestampNanos=meta.sensorTimestampNanos;this.jpegBytes=jpegBytes;
  }
  public synchronized byte[] copyPng(){return png==null?null:png.clone();}
  @Override public synchronized void close(){if(png!=null){Arrays.fill(png,(byte)0);png=null;}}
 }
 private final Context context;private final Surface surface;private final CameraModule.Config config;private final Listener listener;
 interface CameraProvider { CameraModule create(Context context,Surface surface,long timeout,Runnable released); }
 private final CameraProvider cameras;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final PreviewReadiness readiness=new PreviewReadiness(300,500);
 private final DebugCaptureArm arm=new DebugCaptureArm();private final Object lock=new Object();
 private final String id="debug-shot-"+UUID.randomUUID();
 private StandardCameraCapture capture;private CameraModule module;
 private boolean started,cancelled,finished,released,terminal,shownReady;private int jpegBytes;private byte[] pendingPng;
 private final Runnable closingDeadline=this::closingUnconfirmed;
 private void closingUnconfirmed(){synchronized(lock){if(terminal)return;}listener.status(DebugPrivatePreview.Status.CLOSE_UNCONFIRMED);}
 public DebugSingleCapture(Context context,Surface surface,CameraModule.Config config,Listener listener){
  this(context,surface,config,listener,(c,s,t,ack)->Camera2Factory.create(c,s,t,ack));
 }
 DebugSingleCapture(Context context,Surface surface,CameraModule.Config config,Listener listener,CameraProvider cameras){
  if(context==null||surface==null||config==null||listener==null||cameras==null)throw new IllegalArgumentException("Explicit capture owner required");
  this.context=context.getApplicationContext();this.surface=surface;this.config=config;this.listener=listener;
  this.cameras=cameras;
 }
 public void start(){
  synchronized(lock){if(started||cancelled)return;started=true;}
  postStatus(DebugPrivatePreview.Status.OPENING);
  try{
   module=cameras.create(context,surface,120000,this::released);
   AndroidJpegInspector inspector=new AndroidJpegInspector();
   capture=new StandardCameraCapture(module,config,(bytes,c,r)->{
    synchronized(lock){jpegBytes=bytes==null?0:bytes.length;}
    StandardCameraCapture.Image image=inspector.inspect(bytes,c,r);
    try{synchronized(lock){if(!cancelled)pendingPng=image.copy();}}
    catch(RuntimeException|Error failure){image.close();throw failure;}
    return image;
   },120000,new StandardCameraCapture.Preview(){
    public void configured(CameraModule.PreviewInfo info){readiness.configured();main.post(()->{synchronized(lock){if(cancelled||terminal)return;}listener.configured(info);});postStatus(DebugPrivatePreview.Status.PREVIEW);}
    public void frame(long timestamp){readiness.camera(timestamp,now());}
    public boolean ready(long now){
     boolean fresh=readiness.ready(now);arm.ready(fresh);
     synchronized(lock){if(cancelled)return false;if(shownReady!=fresh){shownReady=fresh;main.post(()->{synchronized(lock){if(cancelled||terminal)return;}listener.shotReady(fresh);});}}
     return arm.consume(fresh);
    }
    public void finished(){readiness.invalidate();}
   });
   capture.capture(id,new Verification.Reply<Verification.Capture>(){
    public void ok(Verification.Capture image){
     Result result=null;
     try{
      synchronized(lock){if(cancelled)return;}
      if(image==null||image.provenance==null)throw new IllegalStateException("Missing capture provenance");
      byte[] png;synchronized(lock){png=pendingPng;pendingPng=null;}
      if(png==null)throw new IllegalStateException("Missing normalized memory image");
      result=new Result(png,image.provenance,jpegBytes);
      final Result borrowed=result;result=null;
      main.post(()->{try{boolean deliver;synchronized(lock){deliver=!cancelled;}if(deliver)listener.captured(borrowed);}finally{borrowed.close();complete();}});
     }catch(Exception error){postStatus(DebugPrivatePreview.Status.CONFIGURATION_FAILED);complete();}
     finally{if(result!=null)result.close();}
    }
    public void fail(Verification.Error error){postStatus(error==Verification.Error.PERMISSION_DENIED?DebugPrivatePreview.Status.PERMISSION_DENIED:error==Verification.Error.TIMEOUT?DebugPrivatePreview.Status.TIMEOUT:DebugPrivatePreview.Status.CONFIGURATION_FAILED);complete();}
   });
  }catch(Exception|LinkageError error){synchronized(lock){if(module==null)released=true;}postStatus(DebugPrivatePreview.Status.CONFIGURATION_FAILED);close();}
 }
 private static long now(){return System.nanoTime()/1000000L;}
 public void previewFrame(long timestamp){readiness.surface(timestamp,now());}
 public boolean shoot(){
  synchronized(lock){if(cancelled||finished||terminal)return false;}
  boolean fresh=readiness.ready(now());arm.ready(fresh);boolean accepted=arm.arm();
  if(!accepted&&!arm.requested()){postStatus(DebugPrivatePreview.Status.TIMEOUT);close();}
  return accepted;
 }
 private void postStatus(DebugPrivatePreview.Status status){main.post(()->{synchronized(lock){if(terminal)return;}listener.status(status);});}
 private void released(){synchronized(lock){released=true;}main.post(this::terminalIfDone);}
 private void complete(){
  synchronized(lock){finished=true;if(pendingPng!=null){Arrays.fill(pendingPng,(byte)0);pendingPng=null;}}arm.close();readiness.invalidate();
  if(capture!=null)capture.close();if(module!=null)module.close();
  main.post(this::terminalIfDone);main.postDelayed(closingDeadline,15000);
 }
 private void terminalIfDone(){
  synchronized(lock){if(terminal||!finished||!released)return;terminal=true;}
  main.removeCallbacks(closingDeadline);listener.shotReady(false);listener.status(DebugPrivatePreview.Status.CLOSED);
 }
 @Override public void close(){
  synchronized(lock){if(cancelled)return;cancelled=true;if(!started)released=true;}
  postStatus(DebugPrivatePreview.Status.STOPPING);complete();
 }
}
