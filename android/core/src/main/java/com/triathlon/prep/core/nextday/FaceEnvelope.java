package com.triathlon.prep.core.nextday;

import com.triathlon.prep.core.Verification;
import java.io.*;import java.nio.ByteBuffer;import java.security.*;import java.util.*;
import javax.crypto.*;import javax.crypto.spec.GCMParameterSpec;

/** Versioned authenticated transfer format. No device-bound key, photo or activation material in the format. */
public final class FaceEnvelope {
 private FaceEnvelope(){}
 public static final int MAX_BYTES=64*1024*1024;
 private static final int MAGIC=0x544e3339,HEADER=21,TAG_BYTES=16;
 public static SecretKey newTransferKey()throws IOException{try{KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);return generator.generateKey();}catch(GeneralSecurityException e){throw new IOException("Transfer key unavailable",e);}}
 public static byte[] encrypt(FaceLibrary.Snapshot snapshot,SecretKey key)throws IOException {
  if(snapshot==null||key==null)throw new IllegalArgumentException("Snapshot and explicit key required");byte[] clear=encode(snapshot);
  try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);byte[] nonce=cipher.getIV();if(nonce==null||nonce.length!=12)throw new IOException("Unsupported encryption nonce");byte[] header=ByteBuffer.allocate(HEADER).putInt(MAGIC).put((byte)1).put(nonce).putInt(clear.length+TAG_BYTES).array();cipher.updateAAD(header);byte[] encrypted=cipher.doFinal(clear);ByteArrayOutputStream result=new ByteArrayOutputStream(header.length+encrypted.length);result.write(header);result.write(encrypted);if(result.size()>MAX_BYTES)throw new IOException("Face package capacity exceeded");return result.toByteArray();}
  catch(GeneralSecurityException e){throw new IOException("Face package encryption failed",e);}finally{Arrays.fill(clear,(byte)0);}
 }
 public static FaceLibrary.Snapshot decrypt(byte[] packageBytes,SecretKey key,FaceLibrary.Scope expected)throws IOException {
  if(key==null||expected==null)throw new IllegalArgumentException("Expected scope and explicit key required");
  FaceLibrary.Snapshot decoded=decryptAuthenticated(packageBytes,key);if(!expected.equals(decoded.scope))throw new IOException("Face package mode/event/model mismatch");return decoded;
 }
 /** Returns only fully authenticated and structurally validated content, not selection authority. */
 public static FaceLibrary.Snapshot decryptAuthenticated(byte[] packageBytes,SecretKey key)throws IOException {
  if(key==null)throw new IllegalArgumentException("Explicit key required");
  if(packageBytes==null||packageBytes.length<HEADER+TAG_BYTES||packageBytes.length>MAX_BYTES)throw new IOException("Face package size invalid");
  ByteBuffer header=ByteBuffer.wrap(packageBytes,0,HEADER);if(header.getInt()!=MAGIC||header.get()!=1)throw new IOException("Face package format incompatible");byte[] nonce=new byte[12];header.get(nonce);int length=header.getInt();if(length!=packageBytes.length-HEADER)throw new IOException("Face package length invalid");byte[] clear=null;
  try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(packageBytes,0,HEADER);clear=cipher.doFinal(packageBytes,HEADER,length);return decode(clear);}
  catch(GeneralSecurityException|IllegalArgumentException e){throw new IOException("Face package authentication or content rejected",e);}finally{if(clear!=null)Arrays.fill(clear,(byte)0);}
 }
 private static byte[] encode(FaceLibrary.Snapshot snapshot)throws IOException {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);FaceLibrary.Scope scope=snapshot.scope;
  out.writeUTF(snapshot.revision);out.writeUTF(scope.mode.name());out.writeUTF(scope.batchId);out.writeUTF(scope.modelFingerprint);out.writeUTF(scope.featureType);out.writeInt(scope.featureLength);out.writeInt(snapshot.entries.size());
  for(FaceLibrary.Entry row:snapshot.entries){out.writeInt(row.id);out.writeUTF(row.bib);out.writeUTF(row.name);out.writeUTF(row.group);out.writeLong(row.enrolledAt);byte[] feature=row.feature();try{out.writeInt(feature.length);out.write(feature);}finally{Arrays.fill(feature,(byte)0);}}
  out.flush();if(bytes.size()>MAX_BYTES-HEADER-TAG_BYTES)throw new IOException("Face package capacity exceeded");return bytes.toByteArray();
 }
 private static FaceLibrary.Snapshot decode(byte[] bytes)throws IOException {
  try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));String revision=in.readUTF();Verification.Mode mode=Verification.Mode.valueOf(in.readUTF());FaceLibrary.Scope scope=new FaceLibrary.Scope(mode,in.readUTF(),in.readUTF(),in.readUTF(),in.readInt());int count=in.readInt();if(count<0||count>FaceLibrary.MAX_PEOPLE)throw new IOException("Face count invalid");List<FaceLibrary.Entry> rows=new ArrayList<>(count);
   for(int i=0;i<count;i++){int id=in.readInt();String bib=in.readUTF(),name=in.readUTF(),group=in.readUTF();long stamp=in.readLong();int length=in.readInt();if(length!=scope.featureLength)throw new IOException("Face feature length invalid");byte[] feature=new byte[length];try{in.readFully(feature);rows.add(new FaceLibrary.Entry(id,bib,name,group,feature,stamp));}finally{Arrays.fill(feature,(byte)0);}}
   if(in.available()!=0)throw new IOException("Trailing face data");return new FaceLibrary.Snapshot(revision,scope,rows);
  }catch(IllegalArgumentException e){throw new IOException("Face content invalid",e);}
 }
}
