package com.triathlon.prep;
import android.content.Context;
import com.triathlon.prep.nextday.baidu.BaiduRuntimePreparation;
import com.triathlon.prep.nextday.host.*;
import com.triathlon.prep.nextday.storage.FaceLibraryKeyStore;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

/** Maintenance seam only; expected binding comes from trusted Host maintenance, never profile booleans. */
final class PrivatePreparationMaintenance {
 interface Target extends AuthenticatedProfileInstaller.Guard {
  BaiduRuntimePreparation.Binding expected();
  /** Revoke old runtime observations/operations before profile replacement. */
  void invalidateRuntime()throws IOException;
 }
 static void install(Context app,InputStream input,Target target)throws IOException{
  if(app==null||input==null||target==null||!target.current())throw new IOException("Trusted maintenance target required");
  BaiduRuntimePreparation.Binding expected=target.expected();
  if(expected==null)throw new IOException("Trusted maintenance target required");
  if(!app.getPackageName().equals(expected.appPackage))throw new IOException("Maintenance app binding mismatch");
  byte[] plain=null;
  try{
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int count;
   while((count=input.read(block))!=-1){if(bytes.size()+count>AuthenticatedProfileInstaller.MAX_PLAIN)throw new IOException("Profile capacity exceeded");bytes.write(block,0,count);}
   plain=bytes.toByteArray();validate(app,expected,plain);
   if(!target.current())throw new IOException("Maintenance revoked");
   target.invalidateRuntime();
   File file=new File(app.getNoBackupFilesDir().getCanonicalFile(),"nextday-runtime/"+expected.scope.mode.name()+"/preparation.dat");
   AuthenticatedProfileInstaller installer=new AuthenticatedProfileInstaller(new AtomicProfilePacketStore(file),FaceLibraryKeyStore.loadOrCreate(app,expected.scope.mode),app.getPackageName(),expected.scope.mode);
   installer.install(plain,copy->validate(app,expected,copy),target);
  }finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
 }
 static File generationFile(Context app)throws IOException{return new File(app.getNoBackupFilesDir().getCanonicalFile(),"nextday-runtime/generation.dat");}
 static String generation(Context app)throws IOException{
  File path=generationFile(app);if(!path.getCanonicalFile().equals(path.getAbsoluteFile()))throw new IOException("Generation path invalid");
  android.util.AtomicFile file=new android.util.AtomicFile(path);
  try(DataInputStream input=new DataInputStream(file.openRead())){byte[] bytes=new byte[32];input.readFully(bytes);if(input.read()!=-1)throw new IOException("Generation invalid");String value=new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);if(!value.matches("[a-f0-9]{32}"))throw new IOException("Generation invalid");return value;}
  catch(FileNotFoundException missing){if(path.exists()||new File(path+".bak").exists()||new File(path+".new").exists())throw new IOException("Generation recovery unavailable",missing);return "ABSENT";}
 }
 static void invalidate(Context app)throws IOException{
  File path=generationFile(app);File parent=path.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Generation folder unavailable");android.util.AtomicFile file=new android.util.AtomicFile(path);String next=java.util.UUID.randomUUID().toString().replace("-","");FileOutputStream out=file.startWrite();try{out.write(next.getBytes(java.nio.charset.StandardCharsets.US_ASCII));file.finishWrite(out);out=null;if(!next.equals(generation(app)))throw new IOException("Generation readback failed");}finally{if(out!=null)file.failWrite(out);}
 }
 static com.triathlon.prep.core.nextday.FaceEngine.RuntimeGate guarded(Context app,com.triathlon.prep.core.nextday.FaceEngine.RuntimeGate base)throws IOException{
  final String captured=generation(app);
  return new com.triathlon.prep.core.nextday.FaceEngine.RuntimeGate(){
   private boolean current(){try{return captured.equals(generation(app));}catch(IOException|RuntimeException e){return false;}}
   public boolean nativeInitializationAllowed(){return current()&&base.nativeInitializationAllowed();}
   public boolean licenceVerified(){return current()&&base.licenceVerified();}
   public com.triathlon.prep.core.nextday.FaceEngine.Parameters parameters(){com.triathlon.prep.core.nextday.FaceEngine.Parameters p=base.parameters();return p==null?null:new com.triathlon.prep.core.nextday.FaceEngine.Parameters(p.revision,p.matchThreshold,p.minimumMargin,p.maximumFrameAgeMillis,p.calibrated&&current());}
  };
 }
 static void validate(Context app,BaiduRuntimePreparation.Binding expected,byte[] plain)throws IOException{
  try{
   StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(plain))hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
   if(!hash.toString().equals(expected.profileSHA256))throw new IOException("Maintenance approved profile mismatch");
   BaiduRuntimePreparation.PreparedRuntime prepared=BaiduRuntimePreparation.prepare(expected.scope,()->plain.clone(),path->app.getAssets().open(path),BaiduRuntimePreparation.UNVERIFIED);
   if(prepared.configuration()==null||prepared.parameters()==null)throw new IOException("Profile schema/models/policy not prepared");
   JSONObject object=new JSONObject(new String(plain,java.nio.charset.StandardCharsets.UTF_8));
   if(!expected.appPackage.equals(object.getString("appPackage"))||!expected.deviceBindingHash.equals(object.getString("deviceBindingHash"))||!expected.sdkIdentity.equals(object.getString("sdkIdentity"))||expected.configurationRevision!=object.getLong("configurationRevision"))throw new IOException("Maintenance target binding mismatch");
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Maintenance validation failed",e);}
 }
}

