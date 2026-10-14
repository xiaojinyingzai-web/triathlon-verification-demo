package com.triathlon.prep;
import java.io.*;import java.security.MessageDigest;
/** Explicit operator SDK-service scope, bound to exact installed APK, expires within 15 minutes. */
public final class ReaderServiceApproval {
 public enum InvalidReason { FORMAT, APK_BINDING, SCOPE, EXPIRED, CLOCK_WINDOW }
 public static final class InvalidApproval extends IOException {
  private static final long serialVersionUID=1L;public final InvalidReason reason;
  InvalidApproval(InvalidReason reason){super("Reader approval requires review");this.reason=reason;}
 }
 public static final String HOST="yfs4.sfzydq.com";public static final int PORT=9999;
 private final long expires;
 private ReaderServiceApproval(long expires){this.expires=expires;}
 public static ReaderServiceApproval decode(byte[] bytes,byte[] apkHash,long now)throws IOException{
  if(apkHash==null||apkHash.length!=32)throw new IOException("Current APK binding unavailable");
  if(bytes==null||bytes.length!=45)throw new InvalidApproval(InvalidReason.FORMAT);
  try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
   if(in.readInt()!=0x52445031)throw new InvalidApproval(InvalidReason.FORMAT);
   byte[] binding=new byte[32];in.readFully(binding);long expires=in.readLong();int flags=in.readUnsignedByte();
   // 1: required card-data service, 2: official initialization download, 4: SDK-managed fallback pool.
   if(!MessageDigest.isEqual(binding,apkHash))throw new InvalidApproval(InvalidReason.APK_BINDING);
   if(flags!=7)throw new InvalidApproval(InvalidReason.SCOPE);
   if(expires<=now)throw new InvalidApproval(InvalidReason.EXPIRED);
   if(now<0||expires-now>900000)throw new InvalidApproval(InvalidReason.CLOCK_WINDOW);
   return new ReaderServiceApproval(expires);
  }
 }
 public boolean validAt(long now){return now>=0&&now<expires;}
}
