package com.triathlon.prep.nextday.baidu;

import android.content.Context;import android.graphics.*;
import com.baidu.idl.main.facesdk.*;import com.baidu.idl.main.facesdk.model.*;import com.baidu.idl.main.facesdk.callback.Callback;
import com.triathlon.prep.core.nextday.*;
import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;

/** Real V9 declarations only; created exclusively after the engine's runtime gate. No activation calls. */
final class BaiduV9Bridge implements BaiduFaceEngine.SdkPort {
 private final Context context;private final BaiduV9Configuration configuration;
 private BDFaceInstance instance;private FaceDetect detect;private FaceLive live;private FaceFeature feature;private FaceSearch search;
 private final List<byte[]> templates=new ArrayList<>();
 private final ReleasePlan releasePlan=new ReleasePlan();
 BaiduV9Bridge(Context context,BaiduV9Configuration configuration){this.context=context;this.configuration=configuration;}
 interface Loader{void load(Callback callback);}
 private void await(Loader loader)throws IOException{
  CountDownLatch complete=new CountDownLatch(1);AtomicInteger code=new AtomicInteger(Integer.MIN_VALUE);AtomicBoolean accepting=new AtomicBoolean(true);
  try{loader.load((status,msg)->{if(accepting.compareAndSet(true,false)){code.set(status);complete.countDown();}});
   if(!complete.await(configuration.initializationTimeoutMillis,TimeUnit.MILLISECONDS)||code.get()!=0)throw new IOException("BAIDU_MODEL_LOAD_FAILED");
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("BAIDU_MODEL_LOAD_CANCELLED");}finally{accepting.set(false);}
 }
 public void initialize()throws IOException{
  if(context==null)throw new IOException("BAIDU_CONTEXT_REQUIRED");instance=new BDFaceInstance();instance.creatInstance();
  detect=new FaceDetect(instance);live=new FaceLive(instance);feature=new FaceFeature(instance);search=new FaceSearch(instance);
  BDFaceSDKConfig c=new BDFaceSDKConfig();c.maxDetectNum=10;c.minFaceSize=configuration.minimumFaceSize;
  c.isCheckBlur=true;c.isOcclusion=true;c.isIllumination=true;c.isHeadPose=true;detect.loadConfig(c);
  await(cb->detect.initModel(context,configuration.detect[0],configuration.detect[1],BDFaceSDKCommon.DetectType.DETECT_VIS,BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,cb));
  await(cb->detect.initQuality(context,configuration.quality[0],configuration.quality[1],cb));
  await(cb->live.initModel(context,configuration.live[0],configuration.live[1],configuration.live[2],configuration.live[3],configuration.live[4],configuration.live[5],cb));
  await(cb->feature.initModel(context,configuration.feature[0],configuration.feature[1],configuration.feature[2],configuration.feature[3],cb));
 }
 public void clear()throws IOException{if(search.featureClear()!=configuration.indexSuccessCode||search.getSize()!=0)throw new IOException("BAIDU_CLEAR_FAILED");wipeTemplates();}
 public void push(int id,byte[] data)throws IOException{
  byte[] owned=data.clone();if(search.pushPersonById(id,owned)!=configuration.indexSuccessCode){Arrays.fill(owned,(byte)0);throw new IOException("BAIDU_PUSH_FAILED");}templates.add(owned);
 }
 public int size(){return search.getSize();}
 private boolean quality(FaceInfo info){
  if(info==null||info.landmarks==null||info.landmarks.length<10||info.occlusion==null)return false;
  for(float f:info.landmarks)if(!Float.isFinite(f))return false;
  float[] values={info.score,info.bluriness,info.illum,info.yaw,info.pitch,info.roll,info.occlusion.leftEye,info.occlusion.rightEye,info.occlusion.nose,info.occlusion.mouth,info.occlusion.leftCheek,info.occlusion.rightCheek,info.occlusion.chin};
  for(float f:values)if(!Float.isFinite(f))return false;
  if(info.score<configuration.minimumDetect||info.bluriness>configuration.maximumBlur||info.illum<configuration.minimumIllumination||Math.abs(info.yaw)>configuration.maximumPose||Math.abs(info.pitch)>configuration.maximumPose||Math.abs(info.roll)>configuration.maximumPose)return false;
  for(int i=6;i<values.length;i++)if(values[i]>configuration.maximumOcclusion)return false;return true;
 }
 public FaceEngine.Evaluation evaluate(CapturePort.Frame frame,boolean requireLive)throws IOException{
  byte[] data=frame.image();Bitmap bitmap=null,oriented=null;BDFaceImageInstance image=null;byte[] output=new byte[512];
  try{
   if(frame.type==CapturePort.ImageType.NV21){ByteArrayOutputStream encoded=new ByteArrayOutputStream();if(!new YuvImage(data,ImageFormat.NV21,frame.width,frame.height,null).compressToJpeg(new Rect(0,0,frame.width,frame.height),100,encoded))throw new IOException("BAIDU_FRAME_DECODE_FAILED");Arrays.fill(data,(byte)0);data=encoded.toByteArray();}
   BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);
   if(bounds.outWidth!=frame.width||bounds.outHeight!=frame.height)throw new IOException("BAIDU_FRAME_SIZE_MISMATCH");
   bitmap=BitmapFactory.decodeByteArray(data,0,data.length);if(bitmap==null)throw new IOException("BAIDU_FRAME_DECODE_FAILED");
   Matrix matrix=new Matrix();matrix.postRotate(frame.rotation);if(frame.mirror)matrix.postScale(-1,1);
   oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);image=new BDFaceImageInstance(oriented);
   BDFaceDetectListConf conf=new BDFaceDetectListConf();conf.usingDetect=true;conf.usingAlign=true;conf.usingQuality=true;conf.usingHeadPose=true;
   FaceInfo[] faces=detect.detect(BDFaceSDKCommon.DetectType.DETECT_VIS,BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,image,null,conf);
   if(faces==null||faces.length!=1||!quality(faces[0]))return new FaceEngine.Evaluation(FaceEngine.StepResult.FAILED,FaceEngine.StepResult.UNKNOWN,null);
   FaceEngine.StepResult liveness=FaceEngine.StepResult.NOT_REQUIRED;
   if(requireLive){float score=live.silentLive(BDFaceSDKCommon.LiveType.BDFACE_SILENT_LIVE_TYPE_RGB,image,faces[0].landmarks,configuration.minimumLive);
    if(!Float.isFinite(score)||score<configuration.minimumLive)return new FaceEngine.Evaluation(FaceEngine.StepResult.PASSED,FaceEngine.StepResult.FAILED,null);liveness=FaceEngine.StepResult.PASSED;}
   float code=feature.feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,image,faces[0].landmarks,output);
   return new FaceEngine.Evaluation(FaceEngine.StepResult.PASSED,liveness,code==configuration.featureSuccessCode?output:null);
  }finally{Arrays.fill(data,(byte)0);Arrays.fill(output,(byte)0);if(image!=null)image.destory();if(oriented!=null&&oriented!=bitmap)oriented.recycle();if(bitmap!=null)bitmap.recycle();}
 }
 public List<FaceEngine.Candidate> search(byte[] data,int count,double threshold)throws IOException{
  if(threshold>Float.MAX_VALUE)throw new IOException("BAIDU_THRESHOLD_INVALID");
  List<? extends Feature> found=search.search(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,(float)threshold,count,data,true);
  if(found==null)throw new IOException("BAIDU_SEARCH_FAILED");List<FaceEngine.Candidate> out=new ArrayList<>();for(Feature f:found){if(f==null)throw new IOException("BAIDU_SEARCH_INVALID");out.add(new FaceEngine.Candidate(f.getId(),f.getScore()));}return out;
 }
 private void wipeTemplates(){for(byte[] bytes:templates)Arrays.fill(bytes,(byte)0);templates.clear();}
 interface ReleaseStep {void run()throws IOException;}
 /** A root instance must remain owned until every model release has completed. */
 static final class ReleasePlan {
  final boolean[] done=new boolean[4];
  void close(ReleaseStep... steps)throws IOException {
   if(steps.length!=4)throw new IllegalArgumentException("Four release steps required");IOException failure=null;
   for(int i=0;i<3;i++)if(!done[i])try{steps[i].run();done[i]=true;}catch(IOException|RuntimeException|LinkageError e){failure=new IOException("BAIDU_MODEL_RELEASE_PENDING");}
   if(failure!=null)throw failure;
   if(!done[3])try{steps[3].run();done[3]=true;}catch(IOException|RuntimeException|LinkageError e){throw new IOException("BAIDU_INSTANCE_RELEASE_PENDING");}
  }
 }
 public void close()throws IOException{
  releasePlan.close(
   ()->{if(detect!=null&&detect.uninitModel()!=0)throw new IOException("BAIDU_DETECT_RELEASE_PENDING");detect=null;},
   ()->{if(live!=null&&live.uninitModel()!=0)throw new IOException("BAIDU_LIVE_RELEASE_PENDING");live=null;},
   ()->{if(feature!=null&&feature.uninitModel()!=0)throw new IOException("BAIDU_FEATURE_RELEASE_PENDING");feature=null;},
   ()->{if(instance!=null&&instance.destory()!=0)throw new IOException("BAIDU_INSTANCE_RELEASE_PENDING");instance=null;search=null;wipeTemplates();});
 }
}
