package com.triathlon.prep.nextday.baidu;
import java.io.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;import javax.crypto.*;import javax.crypto.spec.GCMParameterSpec;
import com.triathlon.prep.core.Verification;
/** Only reads a Host-owned encrypted private profile; never creates files, directories or keys. */
final class PreparationProfile {
 static BaiduRuntimePreparation.Source source(File noBackup,Verification.Mode mode,File supplied,SecretKey key,String appPackage)throws IOException{
  if(noBackup==null||mode==null||supplied==null||!BaiduRuntimePreparation.packageName(appPackage))throw new IOException("PRIVATE_SOURCE_PATH");
  File base=noBackup.getCanonicalFile(),expected=new File(base,"nextday-runtime/"+mode.name()+"/preparation.dat");
  if(!expected.getCanonicalFile().equals(expected.getAbsoluteFile())||!supplied.getCanonicalFile().equals(expected.getAbsoluteFile()))throw new IOException("PRIVATE_SOURCE_PATH");
  return ()->{
   if(!expected.exists())throw new FileNotFoundException("PREPARATION_SOURCE_MISSING");
   if(!expected.isFile()||!expected.getCanonicalFile().equals(expected.getAbsoluteFile()))throw new BaiduRuntimePreparation.PreparationFailure(BaiduRuntimePreparation.Status.PRIVATE_AUTH_FAILED,"privateSource.path");
   if(key==null)throw new BaiduRuntimePreparation.PreparationFailure(BaiduRuntimePreparation.Status.PRIVATE_AUTH_FAILED,"localKey");
   byte[] packet=null,plain=null,nonce=new byte[12];
   try{
    try(InputStream in=new FileInputStream(expected)){packet=BaiduRuntimePreparation.read(in,65536);}if(packet.length<32)throw new IOException("PRIVATE_SOURCE_FRAME");
    DataInputStream header=new DataInputStream(new ByteArrayInputStream(packet));if(header.readInt()!=1)throw new IOException("PRIVATE_SOURCE_VERSION");header.readFully(nonce);
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(appPackage,mode));plain=cipher.doFinal(packet,16,packet.length-16);
    Map<String,Object> object=PreparationJson.read(plain);if(!appPackage.equals(object.get("appPackage")))throw new IOException("PRIVATE_SOURCE_PACKAGE");byte[] result=plain;plain=null;return result;
   }catch(IOException|GeneralSecurityException|RuntimeException e){throw new BaiduRuntimePreparation.PreparationFailure(BaiduRuntimePreparation.Status.PRIVATE_AUTH_FAILED,"privateSource.authentication");}
   finally{if(packet!=null)Arrays.fill(packet,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);Arrays.fill(nonce,(byte)0);}
  };
 }
 static byte[] aad(String app,Verification.Mode mode){return ("ND_BAIDU_PREPARATION_V1|"+app+"|"+mode.name()).getBytes(StandardCharsets.UTF_8);}
}
