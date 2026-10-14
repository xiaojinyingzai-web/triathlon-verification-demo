package com.triathlon.prep.nextday.storage;
import com.triathlon.prep.core.nextday.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
/** Authenticates total count as well as the empty-snapshot envelope, without a whole-library blob. */
final class StoredMetadata {
 static byte[] seal(FaceLibrary.Snapshot snapshot,SecretKey key)throws IOException {
  byte[] envelope=FaceEnvelope.encrypt(new FaceLibrary.Snapshot(snapshot.revision,snapshot.scope,Collections.emptyList()),key);
  byte[] clear=ByteBuffer.allocate(4+envelope.length).putInt(snapshot.entries.size()).put(envelope).array();
  try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(snapshot.revision.getBytes(StandardCharsets.UTF_8));byte[] iv=c.getIV();if(iv.length!=12)throw new IOException("Metadata nonce invalid");byte[] encrypted=c.doFinal(clear);return ByteBuffer.allocate(12+encrypted.length).put(iv).put(encrypted).array();}
  catch(GeneralSecurityException e){throw new IOException("Metadata encryption failed",e);}finally{Arrays.fill(clear,(byte)0);Arrays.fill(envelope,(byte)0);}
 }
 static void verify(byte[] bytes,SecretKey key,FaceLibrary.Scope scope,String revision,int count)throws IOException {
  if(bytes==null||bytes.length<32||bytes.length>4096)throw new IOException("Metadata size invalid");byte[] clear=null;
  try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,bytes,0,12));c.updateAAD(revision.getBytes(StandardCharsets.UTF_8));clear=c.doFinal(bytes,12,bytes.length-12);ByteBuffer data=ByteBuffer.wrap(clear);if(data.getInt()!=count)throw new IOException("Metadata count invalid");byte[] envelope=new byte[data.remaining()];data.get(envelope);try{FaceLibrary.Snapshot head=FaceEnvelope.decrypt(envelope,key,scope);if(!revision.equals(head.revision)||!head.entries.isEmpty())throw new IOException("Metadata binding invalid");}finally{Arrays.fill(envelope,(byte)0);}}
  catch(GeneralSecurityException|RuntimeException e){throw new IOException("Metadata authentication failed",e);}finally{if(clear!=null)Arrays.fill(clear,(byte)0);}
 }
}
