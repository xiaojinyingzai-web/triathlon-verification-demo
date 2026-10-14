package com.triathlon.prep;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

/** Explicit debug launch, independent of MainActivity/Host/production assembly. */
public class DebugCameraPreviewActivity extends Activity {
 public interface Actions {
  void start(DebugPrivatePreview.Config config,Surface surface);
  void stop();void exit();
  default void startSingleCapture(CameraModule.Config config,Surface surface){}
  default void shoot(){}
  default void previewFrame(Surface owner,long timestamp){}
 }
 public interface Panel {
  View view();void status(DebugPrivatePreview.Status status);
  default void configured(CameraModule.PreviewInfo info){}
  default void shotReady(boolean ready){}
  default void captured(DebugSingleCapture.Result result){}
  default void clearCapture(){}
 }
 public interface PanelFactory { Panel create(Activity activity,Actions actions); }
 private static final int REQUEST_CAMERA=3939;
 private Panel panel;private DebugPrivatePreview preview;
 private DebugSingleCapture singleCapture;private CameraModule.Config pendingSingleConfig;private Surface singleSurface;
 private DebugPrivatePreview.Config pendingConfig;private Surface pendingSurface;
 private boolean foreground,exiting,permissionPending;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  try{
   // Fixed UI implementation supplied by the existing UI owner. No intent-selected class.
   PanelFactory factory=(PanelFactory)Class.forName("com.triathlon.prep.ui.DebugCameraPreviewPanel").getDeclaredConstructor().newInstance();
   panel=factory.create(this,new Actions(){
    @Override public void start(DebugPrivatePreview.Config config,Surface surface){requestStart(config,surface);}
    @Override public void stop(){cancel();}
    @Override public void exit(){exiting=true;cancel();finish();}
    @Override public void startSingleCapture(CameraModule.Config config,Surface surface){requestSingleStart(config,surface);}
    @Override public void shoot(){if(singleCapture!=null&&foreground&&!exiting)singleCapture.shoot();}
    @Override public void previewFrame(Surface owner,long timestamp){if(singleCapture!=null&&owner==singleSurface)singleCapture.previewFrame(timestamp);}
   });
   if(panel==null||panel.view()==null)throw new IllegalStateException("Missing preview panel");
   setContentView(panel.view());
  }catch(Exception missingPanel){finish();} // No fallback to Main/SDK or a new root-owned screen.
 }
 @Override protected void onResume(){super.onResume();foreground=true;}
 @Override protected void onPause(){foreground=false;cancel();super.onPause();}
 @Override protected void onDestroy(){exiting=true;cancel();super.onDestroy();}
 @Override public void onBackPressed(){exiting=true;cancel();super.onBackPressed();}
 private void report(DebugPrivatePreview.Status status){if(panel!=null)panel.status(status);}
 private void requestStart(DebugPrivatePreview.Config config,Surface surface){
  if(!foreground||exiting)return;
  if(preview!=null||singleCapture!=null||permissionPending){report(DebugPrivatePreview.Status.BUSY);return;}
  if(config==null||surface==null||!surface.isValid()){report(DebugPrivatePreview.Status.INVALID_CONFIG);return;}
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
   pendingConfig=config;pendingSurface=surface;permissionPending=true;
   requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA);return;
  }
  start(config,surface);
 }
 private void requestSingleStart(CameraModule.Config config,Surface surface){
  if(!foreground||exiting)return;
  if(preview!=null||singleCapture!=null||permissionPending){report(DebugPrivatePreview.Status.BUSY);return;}
  if(config==null||surface==null||!surface.isValid()){report(DebugPrivatePreview.Status.INVALID_CONFIG);report(DebugPrivatePreview.Status.CLOSED);return;}
  if(panel!=null)panel.clearCapture();
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
   pendingSingleConfig=config;pendingSurface=surface;permissionPending=true;
   requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA);return;
  }
  startSingle(config,surface);
 }
 private void startSingle(CameraModule.Config config,Surface surface){
  if(!foreground||exiting||!surface.isValid()){report(DebugPrivatePreview.Status.CLOSED);return;}
  DebugSingleCapture[] holder=new DebugSingleCapture[1];
  DebugSingleCapture created=createSingleCapture(this,surface,config,new DebugSingleCapture.Listener(){
   public void status(DebugPrivatePreview.Status status){if(singleCapture!=holder[0])return;if(status==DebugPrivatePreview.Status.CLOSED){singleCapture=null;singleSurface=null;}report(status);}
   public void configured(CameraModule.PreviewInfo info){if(singleCapture==holder[0]&&foreground&&!exiting&&panel!=null)panel.configured(info);}
   public void shotReady(boolean ready){if(singleCapture==holder[0]&&panel!=null)panel.shotReady(ready&&foreground&&!exiting);}
   public void captured(DebugSingleCapture.Result result){if(singleCapture==holder[0]&&foreground&&!exiting&&panel!=null)panel.captured(result);}
  });holder[0]=created;singleCapture=created;singleSurface=surface;created.start();
 }
 protected DebugSingleCapture createSingleCapture(android.content.Context context,Surface surface,CameraModule.Config config,DebugSingleCapture.Listener listener){
  return new DebugSingleCapture(context,surface,config,listener);
 }
 private void start(DebugPrivatePreview.Config config,Surface surface){
  if(!foreground||exiting||!surface.isValid())return;
  DebugPrivatePreview[] holder=new DebugPrivatePreview[1];
  DebugPrivatePreview created=createPreview(this,surface,status->{
   if(preview!=holder[0])return;
   if(status==DebugPrivatePreview.Status.CLOSED)preview=null;
   report(status);
  });holder[0]=created;preview=created;created.start(config);
 }
 /** Platform boundary for external instrumentation subclassing; no intent or global override. */
 protected DebugPrivatePreview createPreview(android.content.Context context,Surface surface,DebugPrivatePreview.Listener listener){
  return new DebugPrivatePreview(context,surface,listener);
 }
 private void cancel(){
  boolean waiting=permissionPending;
  pendingConfig=null;pendingSingleConfig=null;pendingSurface=null;
  if(panel!=null){panel.shotReady(false);panel.clearCapture();}
  // A cancelled permission dialog remains pending until its result, preventing another request.
  if(preview!=null)preview.close();
  else if(singleCapture!=null)singleCapture.close();
  else if(waiting)report(DebugPrivatePreview.Status.CLOSED);
 }
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
  super.onRequestPermissionsResult(request,permissions,grants);
  if(request!=REQUEST_CAMERA)return;
  DebugPrivatePreview.Config config=pendingConfig;CameraModule.Config single=pendingSingleConfig;Surface surface=pendingSurface;
  pendingConfig=null;pendingSingleConfig=null;pendingSurface=null;permissionPending=false;
  if(!foreground||exiting||(config==null&&single==null)||surface==null){report(DebugPrivatePreview.Status.CLOSED);return;}
  if(grants.length!=1||grants[0]!=PackageManager.PERMISSION_GRANTED){report(DebugPrivatePreview.Status.PERMISSION_DENIED);report(DebugPrivatePreview.Status.CLOSED);return;}
  if(single!=null)startSingle(single,surface);else start(config,surface);
 }
}
