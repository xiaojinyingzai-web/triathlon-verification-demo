package com.triathlon.prep;
import java.io.*;import java.nio.ByteBuffer;import java.nio.CharBuffer;import java.nio.charset.*;import java.util.Arrays;

/** RDR1, three bounded UTF8 fields. No policy/confirmation/execution grant in this envelope. */
public final class ReaderDiagnosticInput implements AutoCloseable {
 public interface Borrow<T> { T use(String appId,String appKey,String appSecret); }
 private char[] id,key,secret;
 private ReaderDiagnosticInput(char[] id,char[] key,char[] secret){this.id=id;this.key=key;this.secret=secret;}
 public static ReaderDiagnosticInput decode(byte[] bytes)throws IOException{
  if(bytes==null||bytes.length>4096)throw new IOException("Invalid reader input");
  char[] id=null,key=null,secret=null;boolean accepted=false;
  try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
   if(in.readInt()!=0x52445231)throw new IOException("Invalid reader input");
   id=field(in);key=field(in);secret=field(in);
   if(in.read()!=-1)throw new IOException("Invalid reader input");
   ReaderDiagnosticInput result=new ReaderDiagnosticInput(id,key,secret);accepted=true;return result;
  }finally{if(!accepted){erase(id);erase(key);erase(secret);}}
 }
 private static char[] field(DataInputStream in)throws IOException{
  int size=in.readUnsignedShort();if(size<1||size>1024)throw new IOException("Invalid reader input");
  byte[] bytes=new byte[size];char[] chars=null;boolean accepted=false;
  try{
   in.readFully(bytes);CharBuffer decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
   chars=new char[decoded.remaining()];decoded.get(chars);if(decoded.hasArray())Arrays.fill(decoded.array(),(char)0);
   boolean nonblank=false;for(char c:chars){if(Character.isISOControl(c))throw new IOException("Invalid reader input");if(!Character.isWhitespace(c))nonblank=true;}
   if(!nonblank)throw new IOException("Invalid reader input");accepted=true;return chars;
  }finally{Arrays.fill(bytes,(byte)0);if(!accepted)erase(chars);}
 }
 public synchronized <T>T use(Borrow<T> borrow){
  if(id==null)throw new IllegalStateException("Reader input closed");
  // SDK requires Strings: caller must neither log nor retain. JVM String erasure is not promised.
  return borrow.use(new String(id),new String(key),new String(secret));
 }
 private static void erase(char[] value){if(value!=null)Arrays.fill(value,(char)0);}
 @Override public synchronized void close(){erase(id);erase(key);erase(secret);id=null;key=null;secret=null;}
}
