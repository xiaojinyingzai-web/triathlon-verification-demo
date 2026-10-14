package com.triathlon.prep;
import android.content.Context;import java.io.*;import java.util.Arrays;

/** Fixed app-private one-use credentials. Consumption does not initialize or authorize SDK execution. */
final class ReaderDiagnosticInbox {
 private ReaderDiagnosticInbox(){}
 static synchronized ReaderDiagnosticInput consume(Context context)throws IOException{
  File base=context.getNoBackupFilesDir().getCanonicalFile();File directory=new File(base,"reader-diagnostic");
  if(!directory.getAbsoluteFile().equals(directory.getCanonicalFile()))throw new IOException("Invalid reader inbox");
  if(!directory.isDirectory())throw new IOException("Reader input unavailable");
  File request=new File(directory,"request.bin");
  if(!request.getAbsoluteFile().equals(request.getCanonicalFile())||!request.isFile())throw new IOException("Reader input unavailable");
  byte[] buffer=new byte[4097];int count=0;
  try(FileInputStream stream=new FileInputStream(request)){
   if(!request.delete())throw new IOException("Reader input unavailable");
   while(count<buffer.length){int read=stream.read(buffer,count,buffer.length-count);if(read<0)break;if(read==0)throw new IOException("Invalid reader input");count+=read;}
   if(count>4096)throw new IOException("Invalid reader input");
   byte[] payload=Arrays.copyOf(buffer,count);try{return ReaderDiagnosticInput.decode(payload);}finally{Arrays.fill(payload,(byte)0);}
  }finally{Arrays.fill(buffer,(byte)0);}
 }
 static ReaderServiceApproval approval(Context context)throws Exception{
  File directory=new File(context.getNoBackupFilesDir().getCanonicalFile(),"reader-diagnostic");File file=new File(directory,"service-approval.bin");
  if(!directory.getAbsoluteFile().equals(directory.getCanonicalFile())||!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("Invalid reader approval path");
  if(!file.exists())return null;if(!file.isFile())throw new IOException("Invalid reader approval path");
  byte[] bytes=new byte[45];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){if(!file.delete())throw new IOException("Cannot consume reader approval");try{in.readFully(bytes);}catch(EOFException malformed){return null;}if(in.read()!=-1)return null;}
  java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");byte[] block=new byte[8192];
  try(FileInputStream apk=new FileInputStream(context.getApplicationInfo().sourceDir)){int n;while((n=apk.read(block))!=-1)hash.update(block,0,n);}
  try{return ReaderServiceApproval.decode(bytes,hash.digest(),System.currentTimeMillis());}catch(ReaderServiceApproval.InvalidApproval invalid){return null;}
 }
}
