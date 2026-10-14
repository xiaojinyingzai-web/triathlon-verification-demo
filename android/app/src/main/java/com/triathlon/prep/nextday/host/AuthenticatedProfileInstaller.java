package com.triathlon.prep.nextday.host;

import com.triathlon.prep.core.Verification;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Maintenance-only bounded configuration write. Never grants licence or calibration. */
public final class AuthenticatedProfileInstaller {
 public interface Store {
  /** null only for confirmed never-created; return an owned copy. */
  byte[] read()throws IOException;
  /** Atomically replace; null restores confirmed absence of this owned profile only. */
  void replace(byte[] packet)throws IOException;
 }
 public interface Validator {void validate(byte[] ownedCopy)throws IOException;}
 public interface Write {void run()throws IOException;}
 public interface Guard {boolean current();void commit(Write write)throws IOException;}
 public static final class RestoreFailed extends IOException {RestoreFailed(Throwable cause){super("Profile restoration failed; runtime must remain blocked",cause);}}
 public static final int MAX_PACKET=65536,MAX_PLAIN=MAX_PACKET-32;
 private final Store store;private final SecretKey key;private final byte[] aad;
 public AuthenticatedProfileInstaller(Store store,SecretKey key,String appPackage,Verification.Mode mode){
  this.store=Objects.requireNonNull(store);this.key=Objects.requireNonNull(key);
  if(appPackage==null||!appPackage.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")||mode==null)throw new IllegalArgumentException("Explicit profile namespace required");
  aad=("ND_BAIDU_PREPARATION_V1|"+appPackage+"|"+mode.name()).getBytes(StandardCharsets.UTF_8);
 }
 public void install(byte[] input,Validator validator,Guard guard)throws IOException{
  Objects.requireNonNull(validator);Objects.requireNonNull(guard);
  if(input==null||input.length==0||input.length>MAX_PLAIN)throw new IOException("Profile capacity invalid");
  byte[] plain=input.clone(),validation=plain.clone(),packet=null;
  try{
   validator.validate(validation);
   if(!guard.current())throw new IOException("Profile maintenance revoked");
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad);
   byte[] encrypted=cipher.doFinal(plain),nonce=cipher.getIV();
   try{if(nonce==null||nonce.length!=12)throw new IOException("Profile nonce invalid");ByteArrayOutputStream out=new ByteArrayOutputStream();try(DataOutputStream data=new DataOutputStream(out)){data.writeInt(1);data.write(nonce);data.write(encrypted);}packet=out.toByteArray();}
   finally{Arrays.fill(encrypted,(byte)0);}
   final byte[] proposed=packet;
   guard.commit(()->{
    if(!guard.current())throw new IOException("Profile maintenance revoked before commit");
    byte[] previous=store.read(),actual=null;boolean attempted=false;
    try{
     if(previous!=null&&previous.length>MAX_PACKET)throw new IOException("Existing profile capacity invalid");
     attempted=true;store.replace(proposed);actual=store.read();
     if(!Arrays.equals(proposed,actual))throw new IOException("Profile readback mismatch");
     authenticate(actual,plain);
    }catch(IOException|RuntimeException failure){
     if(attempted)try{store.replace(previous);byte[] restored=store.read();try{if(!Arrays.equals(previous,restored))throw new IOException("Profile rollback readback mismatch");}finally{if(restored!=null)Arrays.fill(restored,(byte)0);}}
     catch(IOException|RuntimeException restoration){restoration.addSuppressed(failure);throw new RestoreFailed(restoration);}
     if(failure instanceof IOException)throw (IOException)failure;throw new IOException("Profile write failed",failure);
    }finally{if(previous!=null)Arrays.fill(previous,(byte)0);if(actual!=null)Arrays.fill(actual,(byte)0);}
   });
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Profile encryption failed",e);}
  finally{Arrays.fill(plain,(byte)0);Arrays.fill(validation,(byte)0);if(packet!=null)Arrays.fill(packet,(byte)0);}
 }
 /** Owned plaintext or null only for confirmed absence; authenticates the exact namespace. */
 public byte[] readAuthenticated()throws IOException{
  byte[] packet=store.read();if(packet==null)return null;
  try{return decrypt(packet);}finally{Arrays.fill(packet,(byte)0);}
 }
 private void authenticate(byte[] packet,byte[] expected)throws IOException{
  byte[] decoded=decrypt(packet);try{if(!Arrays.equals(decoded,expected))throw new IOException("Profile authenticated readback mismatch");}finally{Arrays.fill(decoded,(byte)0);}
 }
 private byte[] decrypt(byte[] packet)throws IOException{
  try{
   if(packet==null||packet.length<32||packet.length>MAX_PACKET||java.nio.ByteBuffer.wrap(packet,0,4).getInt()!=1)throw new IOException("Profile frame invalid");
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,packet,4,12));cipher.updateAAD(aad);
   return cipher.doFinal(packet,16,packet.length-16);
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Profile readback authentication failed",e);}
 }
}
