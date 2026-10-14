package com.triathlon.prep.nextday.storage;

import com.triathlon.prep.core.nextday.FaceEnvelope;
import com.triathlon.prep.core.nextday.FaceLibrary;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Caller-owned streams; authenticated preflight only, never stages or activates a library. */
public final class FaceLibraryTransfer {
 private static final int HEADER=21, MIN_BYTES=37;
 private final int maxBytes;
 public FaceLibraryTransfer(){this(FaceEnvelope.MAX_BYTES);}
 public FaceLibraryTransfer(int maxBytes){if(maxBytes<MIN_BYTES||maxBytes>FaceEnvelope.MAX_BYTES)throw new IllegalArgumentException("Transfer capacity invalid");this.maxBytes=maxBytes;}
 public static SecretKey newKey()throws IOException{return FaceEnvelope.newTransferKey();}
 private static byte[] keyBytes(SecretKey key)throws IOException{
  if(key==null)throw new IllegalArgumentException("Independent transfer key required");
  byte[] bytes=key.getEncoded();
  if(!"AES".equals(key.getAlgorithm())||bytes==null||bytes.length!=32){if(bytes!=null)Arrays.fill(bytes,(byte)0);throw new IOException("Exportable AES256 transfer key required");}
  return bytes;
 }
 public static String encodeKey(SecretKey key)throws IOException{
  byte[] bytes=keyBytes(key);try{StringBuilder text=new StringBuilder("NDK1:");for(byte b:bytes)text.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return text.toString();}finally{Arrays.fill(bytes,(byte)0);}
 }
 public static SecretKey decodeKey(String text)throws IOException{
  if(text==null||!text.matches("NDK1:[0-9a-f]{64}"))throw new IOException("Transfer key encoding invalid");
  byte[] bytes=new byte[32];try{for(int i=0;i<32;i++)bytes[i]=(byte)Integer.parseInt(text.substring(5+2*i,7+2*i),16);return new SecretKeySpec(bytes,"AES");}finally{Arrays.fill(bytes,(byte)0);}
 }
 private static SecretKey portable(SecretKey key)throws IOException {byte[] bytes=keyBytes(key);try{return new SecretKeySpec(bytes,"AES");}finally{Arrays.fill(bytes,(byte)0);}}
 /** Host must pin this READY snapshot on its serial queue. Returned count means write+flush, not durable commit. */
 public long exportReady(FaceLibrary.Snapshot snapshot,FaceLibrary.Status status,SecretKey key,OutputStream output)throws IOException {
  if(snapshot==null||output==null)throw new IllegalArgumentException("Snapshot and output required");
  if(status!=FaceLibrary.Status.READY||snapshot.entries.isEmpty())throw new IOException("Current nonempty library must be ready");
  byte[] encrypted=FaceEnvelope.encrypt(snapshot,portable(key));
  try{if(encrypted.length>maxBytes)throw new IOException("Transfer capacity exceeded");output.write(encrypted);output.flush();return encrypted.length;}
  finally{Arrays.fill(encrypted,(byte)0);}
 }
 public static final class Preflight {
  private final FaceLibrary.Snapshot candidate;
  private Preflight(FaceLibrary.Snapshot candidate){this.candidate=candidate;}
  public FaceLibrary.Snapshot snapshot(){return candidate;}
 }
 public Preflight preflight(InputStream input,SecretKey key,FaceLibrary.Scope expected)throws IOException {
  if(input==null||expected==null)throw new IllegalArgumentException("Input and expected scope required");
  return readPreflight(input,key,expected,false);
 }
 /** Authenticated source-library selection only. Caller must confirm and use a separate source-scope library. */
 public Preflight preflightSource(InputStream input,SecretKey key,FaceLibrary.Scope engineCompatibility)throws IOException {
  if(input==null||engineCompatibility==null)throw new IllegalArgumentException("Input and engine compatibility required");
  return readPreflight(input,key,engineCompatibility,true);
 }
 private Preflight readPreflight(InputStream input,SecretKey key,FaceLibrary.Scope expected,boolean sourceSelection)throws IOException {
  SecretKey transferKey=portable(key);byte[] header=new byte[HEADER],encrypted=null;
  try{
   readFully(input,header,0,HEADER);
   int payload=ByteBuffer.wrap(header,17,4).getInt();
   if(payload<16||payload>maxBytes-HEADER)throw new IOException("Transfer declared length invalid");
   encrypted=new byte[HEADER+payload];System.arraycopy(header,0,encrypted,0,HEADER);readFully(input,encrypted,HEADER,payload);
   if(input.read()!=-1)throw new IOException("Trailing transfer data rejected");
   FaceLibrary.Snapshot candidate=sourceSelection?FaceEnvelope.decryptAuthenticated(encrypted,transferKey):FaceEnvelope.decrypt(encrypted,transferKey,expected);
   if(sourceSelection&&(candidate.scope.mode!=expected.mode||!candidate.scope.modelFingerprint.equals(expected.modelFingerprint)
    ||!candidate.scope.featureType.equals(expected.featureType)||candidate.scope.featureLength!=expected.featureLength))
    throw new IOException("Source library engine incompatible");
   return new Preflight(candidate);
  }finally{Arrays.fill(header,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
 }
 private static void readFully(InputStream input,byte[] target,int offset,int length)throws IOException {
  int end=offset+length;while(offset<end){int n=input.read(target,offset,end-offset);if(n<0)throw new IOException("Transfer truncated");if(n==0){int one=input.read();if(one<0)throw new IOException("Transfer truncated");target[offset++]=(byte)one;}else offset+=n;}
 }
}
