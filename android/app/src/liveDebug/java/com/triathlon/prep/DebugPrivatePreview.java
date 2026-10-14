package com.triathlon.prep;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import java.util.Collections;

/** Debug-only PRIVATE repeating stream. No image reader, still request, business config or SDK. */
public final class DebugPrivatePreview implements AutoCloseable {
 public enum Status { OPENING, PREVIEW, STOPPING, CLOSED, INVALID_CONFIG, PERMISSION_DENIED,
  BUSY, NO_CAMERA, UNSUPPORTED_SIZE, DEVICE_ERROR, CONFIGURATION_FAILED, TIMEOUT, CLOSE_UNCONFIRMED }
 public interface Listener { void status(Status status); }
 /** Package-scoped native boundary; external Android fixture supplies synthetic callbacks. */
 interface CameraPort {
  Status inspect(Config config) throws Exception;
  void open(Config config,CameraDevice.StateCallback callback,Handler handler) throws Exception;
 }
 private static final class AndroidCameraPort implements CameraPort {
  private final CameraManager manager;
  AndroidCameraPort(Context context){manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);}
  @Override public Status inspect(Config config) throws Exception {
   boolean found=false;for(String id:manager.getCameraIdList())if(id.equals(config.cameraId))found=true;
   if(!found)return Status.NO_CAMERA;
   StreamConfigurationMap map=manager.getCameraCharacteristics(config.cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
   Size[] sizes=map==null?null:map.getOutputSizes(ImageFormat.PRIVATE);
   if(sizes!=null)for(Size size:sizes)if(size.getWidth()==config.width&&size.getHeight()==config.height)return null;
   return Status.UNSUPPORTED_SIZE;
  }
  @Override public void open(Config config,CameraDevice.StateCallback callback,Handler handler) throws Exception {
   manager.openCamera(config.cameraId,callback,handler);
  }
 }
 public static final class Config {
  public final String cameraId;public final int width,height,rotationDegrees;
  public Config(String id,int width,int height,int rotationDegrees){
   if(id==null||id.trim().isEmpty()||id.length()>128||width<1||height<1||width>8192||height>8192
      ||!(rotationDegrees==0||rotationDegrees==90||rotationDegrees==180||rotationDegrees==270))throw new IllegalArgumentException("Explicit preview parameters required");
   this.cameraId=id;this.width=width;this.height=height;this.rotationDegrees=rotationDegrees;
  }
 }
 private final Context context;private final Surface surface;private final Listener listener;
 private final CameraPort port;
 private final PreviewOnlyCloseState state=new PreviewOnlyCloseState();
 private final HandlerThread thread=new HandlerThread("DebugPrivatePreview");
 private final Handler worker,main=new Handler(android.os.Looper.getMainLooper());
 private CameraDevice device;private CameraCaptureSession session;
 private boolean leased,finished,openPending;
 private final Runnable openingTimeout=()->fail(Status.TIMEOUT);
 private final Runnable previewTimeout=()->fail(Status.TIMEOUT);
 private final Runnable closingTimeout=()->{if(!state.released())emit(Status.CLOSE_UNCONFIRMED);};
 /** Caller retains Surface/SurfaceTexture until terminal CLOSED or pre-open rejection. */
 public DebugPrivatePreview(Context context,Surface surface,Listener listener){
  this(context,surface,listener,context==null?null:new AndroidCameraPort(context.getApplicationContext()));
 }
 DebugPrivatePreview(Context context,Surface surface,Listener listener,CameraPort port){
  if(context==null||surface==null||listener==null||port==null)throw new IllegalArgumentException("Preview owner required");
  this.context=context.getApplicationContext();this.surface=surface;this.listener=listener;
  this.port=port;
  thread.start();worker=new Handler(thread.getLooper());
 }
 public void start(Config config){
  if(!state.begin())return;
  worker.post(()->open(config));
 }
 private void emit(Status status){main.post(()->listener.status(status));}
 private void reject(Status error){state.openFailed();emit(error);finish();}
 private void open(Config config){
  if(state.released())return;
  // stop() before this queued call closes NEW/OPENING without a native pending request.
  if(stopping){state.openFailed();finish();return;}
  if(config==null||!surface.isValid()){reject(Status.INVALID_CONFIG);return;}
  if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){reject(Status.PERMISSION_DENIED);return;}
  try{
   Status invalid=port.inspect(config);if(invalid!=null){reject(invalid);return;}
   if(!Camera2Factory.RELEASE_GATE.acquire(this)){reject(Status.BUSY);return;}
   leased=true;
   if(stopping){state.openFailed();finish();return;}
   emit(Status.OPENING);worker.postDelayed(openingTimeout,15000);openPending=true;
   port.open(config,new CameraDevice.StateCallback(){
    @Override public void onOpened(CameraDevice camera){
     openPending=false;device=camera;
     if(!state.opened()){camera.close();return;}
     try{camera.createCaptureSession(Collections.singletonList(surface),new CameraCaptureSession.StateCallback(){
      @Override public void onConfigured(CameraCaptureSession configured){
       if(device!=camera||!state.configured()){configured.close();camera.close();return;}
       session=configured;
       if(stopping){stopOnWorker();return;}
       try{
        CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        request.addTarget(surface);
        configured.setRepeatingRequest(request.build(),null,worker);
        worker.removeCallbacks(openingTimeout);worker.postDelayed(previewTimeout,120000);
        if(stopping)stopOnWorker();else emit(Status.PREVIEW);
       }catch(Exception error){fail(Status.CONFIGURATION_FAILED);}
      }
      @Override public void onConfigureFailed(CameraCaptureSession failed){failed.close();fail(Status.CONFIGURATION_FAILED);}
     },worker);}catch(Exception error){fail(Status.CONFIGURATION_FAILED);}
    }
    @Override public void onDisconnected(CameraDevice camera){openPending=false;device=camera;fail(Status.DEVICE_ERROR);}
    @Override public void onError(CameraDevice camera,int error){openPending=false;device=camera;fail(Status.DEVICE_ERROR);}
    @Override public void onClosed(CameraDevice camera){
     if(camera!=device)return;
     device=null;state.deviceClosed();finish();
    }
   },worker);
  }catch(Exception error){
   openPending=false;
   if(device!=null)fail(Status.DEVICE_ERROR);
   else reject(error instanceof SecurityException?Status.PERMISSION_DENIED:Status.DEVICE_ERROR);
  }
 }
 private volatile boolean stopping;
 private void fail(Status status){emit(status);close();}
 @Override public void close(){
  stopping=true;state.stop();worker.post(this::stopOnWorker);
 }
 private void stopOnWorker(){
  if(finished)return;
  worker.removeCallbacks(openingTimeout);worker.removeCallbacks(previewTimeout);
  if(session!=null){session.close();session=null;}
  if(device!=null){device.close();emit(Status.STOPPING);}
  else if(!openPending){state.openFailed();finish();return;}
  else emit(Status.STOPPING);
  worker.removeCallbacks(closingTimeout);worker.postDelayed(closingTimeout,15000);
 }
 private void finish(){
  if(finished||!state.released())return;finished=true;
  worker.removeCallbacksAndMessages(null);
  if(leased){Camera2Factory.RELEASE_GATE.released(this);leased=false;}
  emit(Status.CLOSED);thread.quitSafely();
 }
}
