package com.triathlon.prep.nextday.host;

import android.util.AtomicFile;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.core.nextday.FaceLibrary;
import com.triathlon.prep.core.nextday.FaceLibrarySelection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local authenticated pointer only; banks and CHECKIN records are retained separately. */
public final class EncryptedFaceSelectionStore implements FaceLibrarySelection.Store {
 private final AtomicFile file;private final SecretKey key;private final byte[] aad;
 public EncryptedFaceSelectionStore(File path,SecretKey key,String appPackage,Verification.Mode mode){
  if(path==null||key==null||appPackage==null||mode==null)throw new IllegalArgumentException("Explicit selection namespace required");
  file=new AtomicFile(path);this.key=key;
  aad=("ND_FACE_SELECTION_V1|"+appPackage+"|"+mode.name()).getBytes(StandardCharsets.UTF_8);
 }
 public synchronized FaceLibrarySelection.Choice load()throws IOException {
  byte[] encrypted=null,plain=null;
  try(InputStream input=file.openRead()){
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[1024];int count;
   while((count=input.read(block))!=-1){if(bytes.size()+count>4096)throw new IOException("Selection capacity exceeded");bytes.write(block,0,count);}
   encrypted=bytes.toByteArray();if(encrypted.length<29||encrypted[0]!=1)throw new IOException("Selection format invalid");
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypted,1,12));cipher.updateAAD(aad);
   plain=cipher.doFinal(encrypted,13,encrypted.length-13);
   try(DataInputStream data=new DataInputStream(new ByteArrayInputStream(plain))){
    FaceLibrary.Scope current=scope(data),previous=data.readBoolean()?scope(data):null;
    if(data.read()!=-1)throw new IOException("Trailing selection data");
    return new FaceLibrarySelection.Choice(current,previous);
   }
  }catch(FileNotFoundException missing){
   File base=file.getBaseFile(),parent=base.getAbsoluteFile().getParentFile();
   if(base.exists()||new File(base.getPath()+".bak").exists()||new File(base.getPath()+".new").exists())
    throw new IOException("Selection recovery unavailable",missing);
   // A failed directory listing cannot prove that this bank has never been selected.
   if(parent!=null&&parent.exists()){
    String[] names=parent.list();if(names==null)throw new IOException("Selection absence unconfirmed",missing);
    for(String name:names)if(name.equals(base.getName())||name.equals(base.getName()+".bak")||name.equals(base.getName()+".new"))
     throw new IOException("Selection recovery unavailable",missing);
   }
   return null;
  }
  catch(IOException e){throw e;}catch(Exception e){throw new IOException("Selection authentication failed",e);}
  finally{if(encrypted!=null)Arrays.fill(encrypted,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
 }
 public synchronized void replace(FaceLibrarySelection.Choice choice)throws IOException {
  byte[] plain=null,encrypted=null;FileOutputStream output=null;
  try{
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream data=new DataOutputStream(bytes)){scope(data,choice.current);data.writeBoolean(choice.previous!=null);if(choice.previous!=null)scope(data,choice.previous);}
   plain=bytes.toByteArray();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad);
   byte[] payload=cipher.doFinal(plain),nonce=cipher.getIV();encrypted=new byte[13+payload.length];encrypted[0]=1;System.arraycopy(nonce,0,encrypted,1,12);System.arraycopy(payload,0,encrypted,13,payload.length);Arrays.fill(payload,(byte)0);
   output=file.startWrite();output.write(encrypted);file.finishWrite(output);output=null;
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Selection commit failed",e);}
  finally{if(output!=null)file.failWrite(output);if(plain!=null)Arrays.fill(plain,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
 }
 private static FaceLibrary.Scope scope(DataInputStream data)throws IOException{
  try{return new FaceLibrary.Scope(Verification.Mode.valueOf(data.readUTF()),data.readUTF(),data.readUTF(),data.readUTF(),data.readInt());}
  catch(IllegalArgumentException e){throw new IOException("Selection scope invalid",e);}
 }
 private static void scope(DataOutputStream data,FaceLibrary.Scope scope)throws IOException{
  data.writeUTF(scope.mode.name());data.writeUTF(scope.batchId);data.writeUTF(scope.modelFingerprint);data.writeUTF(scope.featureType);data.writeInt(scope.featureLength);
 }
}
