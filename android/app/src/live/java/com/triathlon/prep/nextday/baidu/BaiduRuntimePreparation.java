package com.triathlon.prep.nextday.baidu;
import android.content.Context;import com.triathlon.prep.core.nextday.*;import com.triathlon.prep.core.Verification;
import java.io.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;import javax.crypto.SecretKey;

/** Configuration preparation only. No native factory/activation/readiness inferred from a profile. */
public final class BaiduRuntimePreparation {
 public interface Source{byte[] read()throws IOException;}
 public interface AssetReader{InputStream open(String assetPath)throws IOException;}
 public interface RuntimeEvidence{boolean permitsNative(Binding binding);boolean licensed(Binding binding);boolean calibrated(Binding binding);}
 public static final RuntimeEvidence UNVERIFIED=new RuntimeEvidence(){public boolean permitsNative(Binding b){return false;}public boolean licensed(Binding b){return false;}public boolean calibrated(Binding b){return false;}};
 public static final String SDK="BaiduV9-20260908";
 static final String AAR="631f8f899f1f389321fe6c9fc35eecbf2482eaea2eea99e4fbc4d49af287200f";
 public enum Status{SOURCE_MISSING,SOURCE_INVALID,PRIVATE_AUTH_FAILED,ASSET_MISSING,HASH_MISMATCH,MODEL_SCOPE_MISMATCH,QUALITY_PROFILE_MISSING,SEARCH_POLICY_MISSING,LICENCE_UNVERIFIED,PARAMETERS_UNVERIFIED,PREPARED_NATIVE_CLOSED}
 public static final class Binding {
  public final FaceLibrary.Scope scope;public final String appPackage,deviceBindingHash,sdkIdentity,profileSHA256;public final long configurationRevision;
  public Binding(FaceLibrary.Scope scope,String appPackage,String deviceBindingHash,String sdkIdentity,String profileSHA256,long configurationRevision){if(scope==null||!packageName(appPackage)||!hash(deviceBindingHash)||!SDK.equals(sdkIdentity)||!hash(profileSHA256)||configurationRevision<1)throw new IllegalArgumentException("Invalid prepared binding");this.scope=scope;this.appPackage=appPackage;this.deviceBindingHash=deviceBindingHash;this.sdkIdentity=sdkIdentity;this.profileSHA256=profileSHA256;this.configurationRevision=configurationRevision;}
 }
 public static final class PreparedRuntime {
  public final Status status;public final List<String> missingFields;public final String modelFingerprint;
  private final BaiduV9Configuration configuration;private final Binding binding;private final RuntimeEvidence evidence;
  private final boolean calibratedOrigin;private final long revision,age;private final double threshold,margin;private final boolean hasPolicy;
  private final FaceEngine.RuntimeGate runtimeGate;
  PreparedRuntime(Status failure,List<String> fields,String fingerprint,BaiduV9Configuration config,Binding binding,RuntimeEvidence evidence,boolean origins,long revision,double threshold,double margin,long age,boolean policy){
   this.configuration=config;this.binding=binding;this.evidence=evidence==null?UNVERIFIED:evidence;modelFingerprint=fingerprint;calibratedOrigin=origins;this.revision=revision;this.threshold=threshold;this.margin=margin;this.age=age;hasPolicy=policy;
   runtimeGate=new FaceEngine.RuntimeGate(){public boolean nativeInitializationAllowed(){return permitted();}public boolean licenceVerified(){return licensed();}public FaceEngine.Parameters parameters(){return PreparedRuntime.this.parameters();}};
   List<String> missing=new ArrayList<>(fields);Status selected=failure;
   if(selected==null){if(!policy){selected=Status.SEARCH_POLICY_MISSING;missing.add("searchPolicy");}else if(!licensed()||!permitted()){selected=Status.LICENCE_UNVERIFIED;if(!licensed())missing.add("runtimeEvidence.licensed");if(!permitted())missing.add("runtimeEvidence.permitsNative");}else if(!calibrated()){selected=Status.PARAMETERS_UNVERIFIED;missing.add("runtimeEvidence.calibrated");}else selected=Status.PREPARED_NATIVE_CLOSED;}
   status=selected;missingFields=Collections.unmodifiableList(missing);
  }
  private boolean licensed(){try{return configuration!=null&&binding!=null&&evidence.licensed(binding);}catch(RuntimeException e){return false;}}
  private boolean permitted(){try{return configuration!=null&&binding!=null&&hasPolicy&&evidence.permitsNative(binding);}catch(RuntimeException e){return false;}}
  private boolean calibrated(){try{return configuration!=null&&binding!=null&&hasPolicy&&calibratedOrigin&&evidence.calibrated(binding);}catch(RuntimeException e){return false;}}
  public BaiduV9Configuration configuration(){return configuration;}
  public FaceEngine.Parameters parameters(){return configuration==null||!hasPolicy?null:new FaceEngine.Parameters(revision,threshold,margin,age,calibrated());}
  public FaceEngine.RuntimeGate gate(){return runtimeGate;}
 }
 static final class PreparationFailure extends IOException{final Status status;final String field;PreparationFailure(Status s,String f){super("PREPARATION_UNAVAILABLE");status=s;field=f;}}
 private static PreparedRuntime failed(Status s,String field,FaceLibrary.Scope scope){return new PreparedRuntime(s,Collections.singletonList(field),scope==null?null:scope.modelFingerprint,null,null,UNVERIFIED,false,0,0,0,0,false);}
 public static PreparedRuntime prepare(FaceLibrary.Scope scope,Source source,AssetReader assets,RuntimeEvidence evidence){
  if(scope==null)return failed(Status.MODEL_SCOPE_MISMATCH,"scope",null);if(source==null)return failed(Status.SOURCE_MISSING,"source",scope);byte[] bytes=null;
  try{
   bytes=source.read();Map<String,Object> p=PreparationJson.read(bytes);
   keys(p,"schema","appPackage","deviceBindingHash","sdkIdentity","configurationRevision","modelFingerprint","qualityProfile","searchPolicy","returnCodes","initializationTimeoutMillis");
   if(integer(p,"schema")!=1||!SDK.equals(string(p,"sdkIdentity")))throw new PreparationFailure(Status.SOURCE_INVALID,"schema");
   String fingerprint=string(p,"modelFingerprint");if(!fingerprint.equals(scope.modelFingerprint)||!"LIVE_PHOTO".equals(scope.featureType)||scope.featureLength!=512)throw new PreparationFailure(Status.MODEL_SCOPE_MISMATCH,"modelFingerprint");
   long revision=integer(p,"configurationRevision"),timeout=p.containsKey("initializationTimeoutMillis")?integer(p,"initializationTimeoutMillis"):10000;
   Binding binding=new Binding(scope,string(p,"appPackage"),string(p,"deviceBindingHash"),SDK,digest(bytes),revision);
   verifyAssets(scope,assets);
   if(p.get("qualityProfile")==null)return failed(Status.QUALITY_PROFILE_MISSING,"qualityProfile",scope);
   Map<String,Object> q=object(p.get("qualityProfile"));keys(q,"origin","evidenceId","minimumDetect","maximumBlur","minimumIllumination","maximumPose","maximumOcclusion","minimumLive","minimumFaceSize");
   boolean qCal=origin(q);safeEvidenceId(q);
   float detection=fraction(q,"minimumDetect"),blur=fraction(q,"maximumBlur"),illum=fraction(q,"minimumIllumination"),occlusion=fraction(q,"maximumOcclusion"),live=fraction(q,"minimumLive");
   double poseValue=number(q,"maximumPose");
   long min=integer(q,"minimumFaceSize");if(poseValue<0||poseValue>180||min<1||min>4096)throw new IOException("PROFILE_RANGE");
   float pose=(float)poseValue;
   Map<String,Object> codes=object(p.get("returnCodes"));keys(codes,"indexSuccessCode","featureSuccessCode");if(integer(codes,"indexSuccessCode")!=0||number(codes,"featureSuccessCode")!=128)throw new IOException("RETURN_CODE_UNSUPPORTED");
   BaiduV9Configuration config=new BaiduV9Configuration(fingerprint,new String[]{PATHS[2],PATHS[0]},new String[]{PATHS[1],PATHS[5]},new String[]{PATHS[6],"","","","",""},new String[]{PATHS[3],PATHS[3],PATHS[4],""},detection,blur,illum,pose,occlusion,live,(int)min,0,128,timeout);
   if(p.get("searchPolicy")==null)return new PreparedRuntime(null,Collections.emptyList(),fingerprint,config,binding,evidence,false,revision,0,0,0,false);
   Map<String,Object> s=object(p.get("searchPolicy"));keys(s,"origin","evidenceId","scoreUnits","matchThreshold","minimumMargin","maximumFrameAgeMillis");boolean sCal=origin(s);safeEvidenceId(s);
   double threshold=number(s,"matchThreshold"),margin=number(s,"minimumMargin");long age=integer(s,"maximumFrameAgeMillis");
   if(!"PERCENT".equals(string(s,"scoreUnits"))||threshold<0||threshold>100||margin<=0||margin>100||age<1||age>60000)throw new IOException("SEARCH_POLICY_RANGE");
   return new PreparedRuntime(null,Collections.emptyList(),fingerprint,config,binding,evidence,qCal&&sCal,revision,threshold,margin,age,true);
  }catch(FileNotFoundException e){return failed(Status.SOURCE_MISSING,"source",scope);}catch(PreparationFailure e){return failed(e.status,e.field,scope);}catch(IOException|RuntimeException e){return failed(Status.SOURCE_INVALID,"profile.schema",scope);}finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
 }
 static final String[] PATHS={"face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4","face-sdk-models/blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3","face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1","face-sdk-models/feature/feature_live-customized-pa-dwtr.model.int8","face-sdk-models/feature/feature_nir-mnasnet-pa-foreign.model.int8-2.0.189.1","face-sdk-models/occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3","face-sdk-models/silent_live/liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1"};
 private static void verifyAssets(FaceLibrary.Scope scope,AssetReader assets)throws IOException{
  if(assets==null)throw new PreparationFailure(Status.ASSET_MISSING,"assets");byte[] manifest;
  try(InputStream in=assets.open("nextday-models.json")){manifest=read(in,65536);}catch(IOException e){throw new PreparationFailure(Status.ASSET_MISSING,"assets.manifest");}
  Map<String,Object> root;try{root=PreparationJson.read(manifest);}finally{Arrays.fill(manifest,(byte)0);}
  keys(root,"modelFingerprint","canonicalPayload","nativeInitializationAllowed","licenceVerified","calibrated");Map<String,Object> p=object(root.get("canonicalPayload"));keys(p,"schema","sdk","aarSHA256","featureType","featureLength","models","optionalSlots");
  if(integer(p,"schema")!=1||!SDK.equals(string(p,"sdk"))||!AAR.equals(string(p,"aarSHA256"))||!scope.featureType.equals(string(p,"featureType"))||integer(p,"featureLength")!=512)throw new PreparationFailure(Status.MODEL_SCOPE_MISMATCH,"assets.sdk");
  if(!scope.modelFingerprint.equals(string(root,"modelFingerprint"))||!scope.modelFingerprint.equals(digest(PreparationJson.canonical(p).getBytes(StandardCharsets.UTF_8))))throw new PreparationFailure(Status.HASH_MISMATCH,"assets.modelFingerprint");
  Map<String,Object> optional=object(p.get("optionalSlots"));keys(optional,"feature3","live1","live2","live3","live4","live5");if(optional.size()!=6)throw new IOException("OPTIONAL_BINDING");for(Object value:optional.values())if(!"".equals(value))throw new IOException("OPTIONAL_BINDING");
  Object raw=p.get("models");if(!(raw instanceof List)||((List<?>)raw).size()!=7)throw new IOException("MODEL_COUNT");
  List<?> list=(List<?>)raw;long total=0;
  for(int i=0;i<7;i++){Map<String,Object> model=object(list.get(i));keys(model,"assetPath","sha256");if(!PATHS[i].equals(string(model,"assetPath"))||!hash(string(model,"sha256")))throw new IOException("MODEL_BINDING");
   try(InputStream in=assets.open(PATHS[i])){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] chunk=new byte[8192];long length=0;int n;
    try{while((n=in.read(chunk))!=-1){if(n==0){int b=in.read();if(b<0)break;chunk[0]=(byte)b;n=1;}length+=n;total+=n;if(length>67108864||total>134217728)throw new PreparationFailure(Status.HASH_MISMATCH,"assets.size");digest.update(chunk,0,n);}if(length==0||!hex(digest.digest()).equals(string(model,"sha256")))throw new PreparationFailure(Status.HASH_MISMATCH,"assets.sha256");}finally{Arrays.fill(chunk,(byte)0);}
   }catch(PreparationFailure e){throw e;}catch(IOException e){throw new PreparationFailure(Status.ASSET_MISSING,"assets.read");}catch(NoSuchAlgorithmException e){throw new IOException("HASH_UNAVAILABLE");}
  }
 }
 static byte[] read(InputStream in,int limit)throws IOException{if(in==null)throw new IOException("STREAM_MISSING");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;try{while((n=in.read(chunk))!=-1){if(n==0){int b=in.read();if(b<0)break;chunk[0]=(byte)b;n=1;}if(out.size()>limit-n)throw new IOException("PREPARATION_SIZE_LIMIT");out.write(chunk,0,n);}return out.toByteArray();}finally{Arrays.fill(chunk,(byte)0);}}
 static String digest(byte[] bytes)throws IOException{try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IOException("HASH_UNAVAILABLE");}}
 static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
 static boolean hash(String v){return v!=null&&v.matches("[a-f0-9]{64}");}
 static boolean packageName(String v){return v!=null&&v.length()<=255&&v.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");}
 static Map<String,Object> object(Object v)throws IOException{if(!(v instanceof Map))throw new IOException("PROFILE_OBJECT");return (Map<String,Object>)v;}
 static void keys(Map<String,Object> p,String... names)throws IOException{Set<String> allowed=new HashSet<>(Arrays.asList(names));for(String key:p.keySet())if(!allowed.contains(key))throw new IOException("PROFILE_UNKNOWN_FIELD");}
 static String string(Map<String,Object> p,String key)throws IOException{Object value=p.get(key);if(!(value instanceof String))throw new IOException("PROFILE_STRING");return (String)value;}
 static long integer(Map<String,Object> p,String key)throws IOException{Object value=p.get(key);if(!(value instanceof Long))throw new IOException("PROFILE_INTEGER");return (Long)value;}
 static double number(Map<String,Object> p,String key)throws IOException{Object value=p.get(key);if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue()))throw new IOException("PROFILE_NUMBER");return ((Number)value).doubleValue();}
 static float fraction(Map<String,Object> p,String key)throws IOException{double d=number(p,key);if(d<0||d>1)throw new IOException("PROFILE_FRACTION");return (float)d;}
 static boolean origin(Map<String,Object> p)throws IOException{String s=string(p,"origin");if(!s.equals("CALIBRATED")&&!s.equals("OFFICIAL_DEMO_INITIAL"))throw new IOException("PROFILE_ORIGIN");return s.equals("CALIBRATED");}
 static void safeEvidenceId(Map<String,Object> p)throws IOException{if(!string(p,"evidenceId").matches("[A-Za-z0-9._:-]{1,128}"))throw new IOException("PROFILE_EVIDENCE_ID");}
 public static PreparedRuntime prepareAndroid(Context app,FaceLibrary.Scope scope,File encryptedProfile,SecretKey localKey,RuntimeEvidence evidence){
  if(app==null||scope==null)return failed(Status.SOURCE_INVALID,"context",scope);
  try{return prepare(scope,PreparationProfile.source(app.getNoBackupFilesDir(),scope.mode,encryptedProfile,localKey,app.getPackageName()),path->app.getAssets().open(path),evidence);}catch(IOException|RuntimeException e){return failed(Status.PRIVATE_AUTH_FAILED,"privateSource.path",scope);}
 }
}
