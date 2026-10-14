package com.triathlon.prep;
import android.content.Context;import com.triathlon.prep.core.*;import com.triathlon.prep.core.nextday.*;import com.triathlon.prep.nextday.host.NextDayModule;import com.triathlon.prep.nextday.baidu.BaiduFaceEngine;
import java.io.*;import java.nio.charset.StandardCharsets;import org.json.JSONObject;
import com.triathlon.prep.nextday.baidu.BaiduRuntimePreparation;
import com.triathlon.prep.nextday.storage.FaceLibraryKeyStore;
import javax.crypto.SecretKey;
/** No activation or licence/config inference. Current trusted evidence is explicitly unavailable. */
final class NextDayFactory {
 static String fingerprint(Context app)throws IOException{
  try(InputStream input=app.getAssets().open("nextday-models.json")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] part=new byte[4096];int count;while((count=input.read(part))!=-1){if(bytes.size()+count>65536)throw new IOException("Model manifest too large");bytes.write(part,0,count);}return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).getString("modelFingerprint");}catch(Exception e){throw new IOException("Model identity unavailable",e);}
 }
 static NextDayModule create(Context app,FaceLibrary.Scope scope,QualifiedSceneSlot scenes,VerificationModeSession authority,Verification.Clock clock,NextDaySurfaceTarget surface)throws IOException{
  return create(app,scope,scenes,authority,clock,surface,BaiduRuntimePreparation.UNVERIFIED);
 }
 /** Evidence must come from a verified, revocable Host collector; profile labels grant no permission. */
 static NextDayModule create(Context app,FaceLibrary.Scope scope,QualifiedSceneSlot scenes,VerificationModeSession authority,Verification.Clock clock,NextDaySurfaceTarget surface,BaiduRuntimePreparation.RuntimeEvidence evidence)throws IOException{
  File file=new File(app.getNoBackupFilesDir(),"nextday-runtime/"+scope.mode.name()+"/preparation.dat");
  SecretKey key=file.exists()?FaceLibraryKeyStore.loadOrCreate(app,scope.mode):null;
  BaiduRuntimePreparation.PreparedRuntime prepared=BaiduRuntimePreparation.prepareAndroid(app,scope,file,key,evidence);
  FaceEngine.RuntimeGate gate=PrivatePreparationMaintenance.guarded(app,prepared.gate());
  FaceEngine engine=new BaiduFaceEngine(app,scope,gate,prepared.configuration());
  NextDayModule module=NextDayLocalFactory.open(app,scope,engine,new LiveNextDayCapture(app,surface),gate,scenes,authority,clock,surface);
  module.preparationNotice(notice(prepared.status));
  return module;
 }
 private static String notice(BaiduRuntimePreparation.Status status){
  switch(status){
   case SOURCE_MISSING:return "尚未投放本机识别配置，请先完成维护准备。";
   case SOURCE_INVALID:return "本机识别配置不完整或不合法，请由维护人员核对。";
   case PRIVATE_AUTH_FAILED:return "本机识别配置未通过完整性校验，请核对配置来源。";
   case ASSET_MISSING:return "本机识别模型缺失，请补齐正式模型包。";
   case HASH_MISMATCH:case MODEL_SCOPE_MISMATCH:return "本机模型与当前识别配置不一致，请核对版本。";
   case QUALITY_PROFILE_MISSING:return "缺少质量检测配置，当前不能开始刷脸核验。";
   case SEARCH_POLICY_MISSING:return "缺少正式识别参数，当前不能开始刷脸核验。";
   case LICENCE_UNVERIFIED:return "识别许可或运行授权尚未满足，当前不能开始刷脸核验。";
   case PARAMETERS_UNVERIFIED:return "本机识别参数尚未完成可信校准，当前不能开始刷脸核验。";
   default:return "识别配置已准备，仍需完成识别引擎初始化及人脸库加载。";
  }
 }
}
