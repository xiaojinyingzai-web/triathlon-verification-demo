package com.triathlon.prep.nextday.records;

import android.util.AtomicFile;
import com.triathlon.prep.core.Verification;
import java.io.*;
import java.util.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

/** One serial Host owner per private file. No report/check-in/count/print side effects. */
public final class NextDayRecordStore {
 public static final int MAX_RECORDS=10000, MAX_FILE_BYTES=2*1024*1024;
 public enum SaveResult { SAVED, ALREADY_SAVED }
 /** Atomic durable storage boundary; read returns null only for confirmed never-created file. */
 public interface FilePort {
  byte[] read(int maximumBytes)throws IOException;
  void replace(byte[] encrypted)throws IOException;
 }
 private final FilePort file;private final SecretKey key;private final Verification.Mode mode;
 public NextDayRecordStore(File privateFile,SecretKey localKey,Verification.Mode expectedMode) {
  this(new AndroidFile(privateFile),localKey,expectedMode);
 }
 public NextDayRecordStore(FilePort file,SecretKey localKey,Verification.Mode expectedMode) {
  if(file==null||localKey==null||expectedMode==null||!"AES".equals(localKey.getAlgorithm()))
   throw new IllegalArgumentException("Private file, AES key and mode required");
  this.file=file;this.key=localKey;this.mode=expectedMode;
 }
 public synchronized List<NextDayRecord> load()throws IOException {
  byte[] encrypted=file.read(MAX_FILE_BYTES);
  if(encrypted==null)return Collections.emptyList();
  if(encrypted.length>MAX_FILE_BYTES||encrypted.length<29)throw new IOException("Invalid record file size");
  byte[] plain=null;
  try {
   Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
   c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(encrypted,0,12)));
   c.updateAAD(aad());plain=c.doFinal(encrypted,12,encrypted.length-12);
   DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain));
   if(in.readInt()!=1)throw new IOException("Record version incompatible");
   int count=in.readInt();if(count<0||count>MAX_RECORDS)throw new IOException("Record limit exceeded");
   List<NextDayRecord> records=new ArrayList<>();Set<String> ids=new HashSet<>();
   for(int i=0;i<count;i++) {
    String id=in.readUTF();Verification.Mode storedMode=Verification.Mode.valueOf(in.readUTF());
    String batch=in.readUTF(),revision=in.readUTF();long parameters=in.readLong();
    int candidate=in.readInt();NextDayRecord.Outcome outcome=NextDayRecord.Outcome.valueOf(in.readUTF());
    NextDayRecord r=new NextDayRecord(id,storedMode,batch,revision,parameters,candidate==0?null:candidate,outcome,in.readLong());
    if(r.mode!=mode||!ids.add(r.operationId))throw new IOException("Invalid record domain or duplicate operation");
    records.add(r);
   }
   if(in.read()!=-1)throw new IOException("Trailing record data");
   return Collections.unmodifiableList(records);
  } catch(GeneralSecurityException|IllegalArgumentException e){throw new IOException("Record authentication or format failed");}
  finally {if(plain!=null)Arrays.fill(plain,(byte)0);}
 }
 public synchronized SaveResult save(NextDayRecord record)throws IOException {
  if(record==null||record.mode!=mode)throw new IllegalArgumentException("Record mode mismatch");
  List<NextDayRecord> records=new ArrayList<>(load());
  for(NextDayRecord r:records)if(r.operationId.equals(record.operationId)) {
   if(r.equals(record))return SaveResult.ALREADY_SAVED;
   throw new IOException("Operation already saved with different result");
  }
  if(records.size()>=MAX_RECORDS)throw new IOException("Record capacity reached");
  records.add(record);byte[] plain=null;
  try {
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
   out.writeInt(1);out.writeInt(records.size());
   for(NextDayRecord r:records) {
    out.writeUTF(r.operationId);out.writeUTF(r.mode.name());out.writeUTF(r.batch);out.writeUTF(r.libraryRevision);
    out.writeLong(r.parameterRevision);out.writeInt(r.candidateId==null?0:r.candidateId);
    out.writeUTF(r.outcome.name());out.writeLong(r.completedAtMillis);
   }
   out.flush();plain=bytes.toByteArray();
   if(plain.length+28>MAX_FILE_BYTES)throw new IOException("Record byte capacity reached");
   Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(aad());
   byte[] nonce=c.getIV();if(nonce==null||nonce.length!=12)throw new IOException("Record cipher nonce incompatible");
   byte[] payload=c.doFinal(plain);byte[] encrypted=new byte[nonce.length+payload.length];
   System.arraycopy(nonce,0,encrypted,0,12);System.arraycopy(payload,0,encrypted,12,payload.length);
   file.replace(encrypted);
   if(!Arrays.equals(encrypted,file.read(MAX_FILE_BYTES)))throw new IOException("Record commit not confirmed");
   return SaveResult.SAVED;
  }catch(GeneralSecurityException e){throw new IOException("Record encryption failed");}
  finally {if(plain!=null)Arrays.fill(plain,(byte)0);}
 }
 private byte[] aad(){return ("triathlon-nextday-records-v1:"+mode.name()).getBytes(StandardCharsets.UTF_8);}
 private static final class AndroidFile implements FilePort {
  private final AtomicFile atomic;
  AndroidFile(File path){if(path==null)throw new IllegalArgumentException("Private file required");atomic=new AtomicFile(path);}
  public byte[] read(int maximum)throws IOException {
   FileInputStream input;
   try{input=atomic.openRead();}
   catch(FileNotFoundException e){
    File base=atomic.getBaseFile();
    if(!base.exists()&&!new File(base.getPath()+".bak").exists()&&!new File(base.getPath()+".new").exists()
      &&base.getParentFile()!=null&&base.getParentFile().isDirectory()&&base.getParentFile().canRead())return null;
    throw new IOException("Record file unavailable");
   }
   try(FileInputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
    byte[] buffer=new byte[4096];int n;
    while((n=in.read(buffer))!=-1){if(out.size()+n>maximum)throw new IOException("Record file too large");out.write(buffer,0,n);}
    return out.toByteArray();
   }
  }
  public void replace(byte[] encrypted)throws IOException {
   FileOutputStream out=null;
   try{out=atomic.startWrite();out.write(encrypted);out.flush();out.getFD().sync();atomic.finishWrite(out);out=null;}
   catch(IOException|RuntimeException e){if(out!=null)atomic.failWrite(out);throw new IOException("Record write failed");}
  }
 }
}
