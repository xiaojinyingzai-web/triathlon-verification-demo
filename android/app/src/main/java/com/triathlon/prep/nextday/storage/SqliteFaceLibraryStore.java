package com.triathlon.prep.nextday.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.core.nextday.FaceLibrary;
import com.triathlon.prep.core.nextday.FaceEnvelope;
import java.io.File;
import java.io.IOException;
import java.util.*;
import javax.crypto.SecretKey;

/** Private row-encrypted version store. Never executes SDK or changes an index. One Host owner. */
public final class SqliteFaceLibraryStore implements FaceLibrary.Store, AutoCloseable {
 private final SQLiteDatabase db; private final SecretKey key; private boolean closed;
 public SqliteFaceLibraryStore(File databaseFile,SecretKey localKey)throws IOException {
  if(databaseFile==null||localKey==null)throw new IllegalArgumentException("Private file and local key required");
  key=localKey;SQLiteDatabase opened=null;
  try {
   opened=SQLiteDatabase.openOrCreateDatabase(databaseFile,null);db=opened;
   int version=db.getVersion();if(version!=0&&version!=1)throw new IOException("Face database version incompatible");
   if(version==0) {
    db.beginTransaction();try {
     db.execSQL("CREATE TABLE versions(revision TEXT PRIMARY KEY, mode TEXT NOT NULL,batch TEXT NOT NULL,model TEXT NOT NULL,type TEXT NOT NULL,length INTEGER NOT NULL,count INTEGER NOT NULL,metadata BLOB NOT NULL)");
     db.execSQL("CREATE TABLE people(revision TEXT NOT NULL,id INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(revision,id))");
     db.execSQL("CREATE TABLE active(slot INTEGER PRIMARY KEY CHECK(slot=1),revision TEXT,ever_active INTEGER NOT NULL CHECK(ever_active IN(0,1)))");
     db.execSQL("INSERT INTO active(slot,revision,ever_active) VALUES(1,NULL,0)");db.setVersion(1);db.setTransactionSuccessful();
    }finally{db.endTransaction();}
   }
  }catch(Exception e){if(opened!=null)opened.close();throw safe(e);}
 }
 private void usable()throws IOException{if(closed)throw new IOException("Face store closed");}
 private static IOException safe(Exception e){return e instanceof IOException?(IOException)e:new IOException("Face storage operation failed",e);}
 private static void revision(String id)throws IOException{try{if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new IOException("Invalid face revision");}}
 private String active()throws IOException {
  try(Cursor c=db.rawQuery("SELECT revision,ever_active FROM active WHERE slot=1",null)) {
   if(!c.moveToFirst())throw new IOException("Face active marker missing");
   if(c.isNull(0)){if(c.getInt(1)!=0)throw new IOException("Face active revision lost");return null;}
   if(c.getInt(1)!=1)throw new IOException("Face active marker inconsistent");return c.getString(0);
  }
 }
 @Override public synchronized FaceLibrary.Snapshot load()throws IOException {
  usable();try {
   db.beginTransaction();try{String id=active();FaceLibrary.Snapshot value=id==null?null:read(id);db.setTransactionSuccessful();return value;}finally{db.endTransaction();}
  }catch(Exception e){throw safe(e);}
 }
 private FaceLibrary.Snapshot read(String id)throws IOException {
  revision(id);FaceLibrary.Scope scope;int count;byte[] metadata;
  try(Cursor c=db.rawQuery("SELECT mode,batch,model,type,length,count,metadata FROM versions WHERE revision=?",new String[]{id})) {
   if(!c.moveToFirst())throw new IOException("Face active version missing");
   scope=new FaceLibrary.Scope(Verification.Mode.valueOf(c.getString(0)),c.getString(1),c.getString(2),c.getString(3),c.getInt(4));count=c.getInt(5);
   if(count<0||count>FaceLibrary.MAX_PEOPLE)throw new IOException("Face row count invalid");metadata=c.getBlob(6);
  }
  StoredMetadata.verify(metadata,key,scope,id,count);
  List<FaceLibrary.Entry> entries=new ArrayList<>(count);
  try(Cursor c=db.rawQuery("SELECT id,payload FROM people WHERE revision=? ORDER BY id",new String[]{id})) {
   while(c.moveToNext()) {
    if(entries.size()>=count)throw new IOException("Unexpected face row");
    FaceLibrary.Snapshot row=FaceEnvelope.decrypt(c.getBlob(1),key,scope);
    if(!id.equals(row.revision)||row.entries.size()!=1||row.entries.get(0).id!=c.getInt(0))throw new IOException("Face row binding invalid");entries.add(row.entries.get(0));
   }
  }
  if(entries.size()!=count)throw new IOException("Face rows incomplete");return new FaceLibrary.Snapshot(id,scope,entries);
 }
 @Override public synchronized void stage(FaceLibrary.Snapshot candidate)throws IOException {
  usable();if(candidate==null)throw new IllegalArgumentException("Candidate required");
  try {
   db.beginTransaction();try {
    ContentValues v=new ContentValues();FaceLibrary.Scope s=candidate.scope;
    v.put("revision",candidate.revision);v.put("mode",s.mode.name());v.put("batch",s.batchId);v.put("model",s.modelFingerprint);v.put("type",s.featureType);v.put("length",s.featureLength);v.put("count",candidate.entries.size());
    v.put("metadata",StoredMetadata.seal(candidate,key));db.insertOrThrow("versions",null,v);
    for(FaceLibrary.Entry e:candidate.entries) {
     ContentValues row=new ContentValues();row.put("revision",candidate.revision);row.put("id",e.id);
     row.put("payload",FaceEnvelope.encrypt(new FaceLibrary.Snapshot(candidate.revision,s,Collections.singletonList(e)),key));db.insertOrThrow("people",null,row);
    }
    db.setTransactionSuccessful();
   }finally{db.endTransaction();}
  }catch(Exception e){throw safe(e);}
 }
 @Override public synchronized void activate(String id)throws IOException {
  usable();revision(id);try {
   db.beginTransaction();try {
    read(id); // Authenticate every row and count before switching durable truth.
    ContentValues v=new ContentValues();v.put("revision",id);v.put("ever_active",1);
    if(db.update("active",v,"slot=1",null)!=1)throw new IOException("Face active marker missing");db.setTransactionSuccessful();
   }finally{db.endTransaction();}
  }catch(Exception e){throw safe(e);}
 }
 @Override public synchronized void discard(String id)throws IOException {
  usable();revision(id);try {
   db.beginTransaction();try {
    if(!id.equals(active())){db.delete("people","revision=?",new String[]{id});db.delete("versions","revision=?",new String[]{id});}db.setTransactionSuccessful();
   }finally{db.endTransaction();}
  }catch(Exception e){throw safe(e);}
 }
 @Override public synchronized void close(){if(!closed){closed=true;db.close();}}
}
