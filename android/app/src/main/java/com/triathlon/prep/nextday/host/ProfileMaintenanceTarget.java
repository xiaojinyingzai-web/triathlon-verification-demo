package com.triathlon.prep.nextday.host;
import java.io.*;import java.util.Objects;
/** Operator-approved deployment target. Never constitutes official device, licence or calibration evidence. */
public final class ProfileMaintenanceTarget {
 public final String installationId,appPackage,modelFingerprint,sdkIdentity,deviceBindingHash,profileSHA256;public final long revision;
 public ProfileMaintenanceTarget(String installation,String app,String model,String sdk,String device,String profile,long revision){
  if(!hash(installation)||app==null||app.length()>255||!app.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")||!hash(model)||!"BaiduV9-20260908".equals(sdk)||!hash(device)||!hash(profile)||revision<1)throw new IllegalArgumentException("Invalid maintenance target");
  installationId=installation;appPackage=app;modelFingerprint=model;sdkIdentity=sdk;deviceBindingHash=device;profileSHA256=profile;this.revision=revision;
 }
 private static boolean hash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
 public void requireLocal(String installation,String app,String model,String sdk)throws IOException{if(!Objects.equals(installationId,installation)||!Objects.equals(appPackage,app)||!Objects.equals(modelFingerprint,model)||!Objects.equals(sdkIdentity,sdk))throw new IOException("Maintenance target is stale or belongs to another installation");}
 public byte[] encode()throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(1);for(String field:new String[]{installationId,appPackage,modelFingerprint,sdkIdentity,deviceBindingHash,profileSHA256})out.writeUTF(field);out.writeLong(revision);}return bytes.toByteArray();}
 public static ProfileMaintenanceTarget decode(byte[] bytes)throws IOException{if(bytes==null||bytes.length>2048)throw new IOException("Maintenance target capacity invalid");try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=1)throw new IOException("Maintenance target version invalid");ProfileMaintenanceTarget target=new ProfileMaintenanceTarget(in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readLong());if(in.read()!=-1)throw new IOException("Maintenance target trailing bytes");return target;}catch(IllegalArgumentException e){throw new IOException("Maintenance target invalid",e);}}
}
