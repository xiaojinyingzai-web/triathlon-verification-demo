package com.triathlon.prep.nextday.host;
import android.content.ContentResolver;import android.net.Uri;import android.provider.DocumentsContract;
import java.io.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;
/** Owns selected provider streams; no SDK/index operations on its IO thread. */
public final class SafFaceTransfer implements AutoCloseable {
 public enum Status { EXPORTED, REVIEW, FAILED, CANCELLED }
 public interface Completion {void completed(String id,Status status,boolean failedOutputCleanupConfirmed);}
 public interface Documents {InputStream read(String uri)throws IOException;OutputStream write(String uri)throws IOException;boolean delete(String uri)throws IOException;}
 private static final class Job {final String id,uri;final boolean exporting;final AtomicBoolean aborted=new AtomicBoolean();volatile Closeable stream;ScheduledFuture<?> timeout;Job(String id,String uri,boolean exporting){this.id=id;this.uri=uri;this.exporting=exporting;}}
 private final FaceLibraryTransferSession session;private final Documents documents;private final long timeoutMillis;
 private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"nextday-file-io");t.setDaemon(true);return t;});
 private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"nextday-file-deadline");t.setDaemon(true);return t;});
 private volatile Job active;private boolean closed;
 public SafFaceTransfer(FaceLibraryTransferSession session,ContentResolver resolver){this(session,new Documents(){
  public InputStream read(String uri)throws IOException{return resolver.openInputStream(Uri.parse(uri));}
  public OutputStream write(String uri)throws IOException{return resolver.openOutputStream(Uri.parse(uri),"w");}
  public boolean delete(String uri)throws IOException{try{return DocumentsContract.deleteDocument(resolver,Uri.parse(uri));}catch(Exception e){throw new IOException("Created output cleanup unavailable",e);}}
 },30000);}
 public SafFaceTransfer(FaceLibraryTransferSession session,Documents documents,long timeoutMillis){if(session==null||documents==null||timeoutMillis<1||timeoutMillis>120000)throw new IllegalArgumentException("Bounded provider IO required");this.session=session;this.documents=documents;this.timeoutMillis=timeoutMillis;}
 public boolean busy(){return active!=null;}
 public void exportTo(String id,String createdDocumentUri,Completion completion)throws IOException{start(id,createdDocumentUri,true,completion);}
 public void readImport(String id,String chosenDocumentUri,Completion completion)throws IOException{start(id,chosenDocumentUri,false,completion);}
 private synchronized void start(String id,String uri,boolean exporting,Completion completion)throws IOException{
  if(closed||active!=null||completion==null||id==null||uri==null||!uri.startsWith("content://"))throw new IOException("Current SAF selection required");
  Job job=new Job(id,uri,exporting);active=job;job.timeout=timer.schedule(()->abort(job),timeoutMillis,TimeUnit.MILLISECONDS);
  try{io.execute(()->run(job,completion));}catch(RejectedExecutionException e){job.timeout.cancel(false);active=null;throw new IOException("File IO closed",e);}
 }
 private void run(Job job,Completion completion){Status status=Status.FAILED;boolean cleanup=true;
  try{
   if(job.aborted.get())throw new IOException("File action cancelled");
   if(job.exporting){try(OutputStream output=documents.write(job.uri)){job.stream=output;if(output==null||job.aborted.get())throw new IOException("Export target unavailable");session.exportTo(job.id,output);}}
   else{try(InputStream input=documents.read(job.uri)){job.stream=input;if(input==null||job.aborted.get())throw new IOException("Import target unavailable");session.readImport(job.id,input);}}
   if(job.aborted.get())throw new IOException("File action cancelled");
   if(job.exporting)session.exportClosed(job.id);else session.importClosed(job.id);
   status=job.exporting?Status.EXPORTED:Status.REVIEW;
  }catch(IOException|RuntimeException failure){session.fileFailed(job.id);status=job.aborted.get()?Status.CANCELLED:Status.FAILED;}
  finally{
   job.stream=null;job.timeout.cancel(false);
   if(job.exporting&&status!=Status.EXPORTED)try{cleanup=documents.delete(job.uri);}catch(IOException|RuntimeException failedCleanup){cleanup=false;}
   synchronized(this){if(active==job)active=null;}
   completion.completed(job.id,status,cleanup);
  }
 }
 private void abort(Job job){synchronized(this){if(active!=job)return;job.aborted.set(true);session.cancel(job.id);}Closeable stream=job.stream;if(stream!=null)try{stream.close();}catch(IOException|RuntimeException ignored){/* The owner finally performs cleanup and reports whether it succeeded. */}}
 /** Caller never waits for provider close on the UI thread. Busy remains true until owner finally exits. */
 public void cancel(){Job job=active;if(job==null)session.cancel();else{session.cancel(job.id);job.aborted.set(true);job.timeout.cancel(false);try{timer.execute(()->abort(job));}catch(RejectedExecutionException ignored){}}}
 public void close(){synchronized(this){if(closed)return;closed=true;}cancel();io.shutdown();timer.shutdown();}
}
