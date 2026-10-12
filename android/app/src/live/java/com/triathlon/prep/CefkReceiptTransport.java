package com.triathlon.prep;

import com.triathlon.prep.core.*;
import com.triathlon.prep.cefk.hardware.CefkPrinter;
import java.util.*;
import java.util.concurrent.*;

/** Dispatch boundary rechecks the current session before a queued job touches the native port. */
public final class CefkReceiptTransport implements ReceiptPrintController.Transport,AutoCloseable {
 private final CefkPrinter printer;private final Object lock=new Object();
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"cefk-print");t.setDaemon(true);return t;});
 private Verification flow;private Job active;private boolean closed,opened;
 private static final class Job{
  final String id,session,text;final Verification.Record record;final ReceiptPrintController.Reply reply;
  volatile boolean cancelled;
  Job(String id,String session,String text,Verification.Record record,ReceiptPrintController.Reply reply){this.id=id;this.session=session;this.text=text;this.record=record;this.reply=reply;}
 }
 public CefkReceiptTransport(CefkPrinter printer){this.printer=Objects.requireNonNull(printer);}
 public void bind(Verification owner){synchronized(lock){if(closed||flow!=null||owner==null||owner.snapshot().mode!=Verification.Mode.LIVE)throw new IllegalStateException("One LIVE owner required");flow=owner;}}
 public Verification.Mode mode(){return Verification.Mode.LIVE;}
 private boolean eligible(Job job){Verification.Snapshot s=flow.snapshot();return s.sessionId.equals(job.session)&&s.result==job.record&&CheckInReceipt.prepare(flow,job.session)!=null;}
 public void print(String id,List<String> lines,ReceiptPrintController.Reply reply){
  Objects.requireNonNull(reply);Verification owner;
  synchronized(lock){owner=flow;}
  if(owner==null){reply.failed(ReceiptPrintController.Failure.NOT_CONNECTED);return;}
  Job job=null;ReceiptPrintController.Failure failure=null;
  synchronized(owner){
   Verification.Snapshot s=owner.snapshot();
   if(CheckInReceipt.prepare(owner,s.sessionId)==null)failure=ReceiptPrintController.Failure.INELIGIBLE;
   else if(id==null||id.isEmpty()||lines==null||lines.isEmpty())failure=ReceiptPrintController.Failure.PRINTER_ERROR;
   else synchronized(lock){
    if(closed)failure=ReceiptPrintController.Failure.NOT_CONNECTED;
    else if(active!=null)failure=ReceiptPrintController.Failure.PRINTER_ERROR;
    else{job=new Job(id,s.sessionId,String.join("\n",lines)+"\n",s.result,reply);active=job;}
   }
  }
  if(failure!=null){reply.failed(failure);return;}
  final Job selected=job;
  try{worker.execute(()->send(selected));}catch(RejectedExecutionException ex){complete(selected,ReceiptPrintController.Failure.NOT_CONNECTED);}
 }
 private void send(Job job){
  ReceiptPrintController.Failure failure=ReceiptPrintController.Failure.PRINTER_ERROR;
  try{
   if(job.cancelled)return;
   boolean current;synchronized(flow){current=eligible(job);}
   if(!current){failure=ReceiptPrintController.Failure.STALE_SESSION;return;}
   if(!opened){
    CefkPrinter.State state=printer.open();opened=true;
    if(state!=CefkPrinter.State.READY){failure=ReceiptPrintController.Failure.NOT_CONNECTED;return;}
   }
   synchronized(flow){
    if(job.cancelled||!eligible(job)){failure=ReceiptPrintController.Failure.STALE_SESSION;return;}
    printer.arm(job.session);
   }
   // Native transfer never holds the core lock. Cancellation cannot retract bytes already sent.
   if(job.cancelled)return;
   CefkPrinter.Result result=printer.printText(job.session,job.text);
   if(result.state==CefkPrinter.State.SUBMITTED_UNCONFIRMED&&result.vendorReturn!=null&&result.vendorReturn>=0)failure=null;
   else if(result.state==CefkPrinter.State.NOT_CONNECTED||result.state==CefkPrinter.State.UNCONFIGURED)failure=ReceiptPrintController.Failure.NOT_CONNECTED;
   else if(result.state==CefkPrinter.State.STALE_SESSION)failure=ReceiptPrintController.Failure.STALE_SESSION;
  }catch(RuntimeException|LinkageError ex){failure=ReceiptPrintController.Failure.PRINTER_ERROR;}
  finally{complete(job,failure);}
 }
 private void complete(Job job,ReceiptPrintController.Failure failure){
  boolean deliver;
  synchronized(lock){deliver=active==job&&!closed&&!job.cancelled;if(active==job)active=null;}
  if(!deliver)return;
  synchronized(flow){if(!eligible(job))failure=ReceiptPrintController.Failure.STALE_SESSION;}
  // Callbacks outside both locks; core performs a further session/job/deadline check.
  if(failure==null)job.reply.accepted();else job.reply.failed(failure);
 }
 public void cancel(String id){synchronized(lock){if(active!=null&&active.id.equals(id)){active.cancelled=true;printer.invalidate();}}}
 @Override public boolean authorizeRetry(String sessionId,boolean possibleDuplicateAcknowledged){
  Verification owner;synchronized(lock){owner=flow;}
  if(!possibleDuplicateAcknowledged||owner==null)return false;
  synchronized(owner){if(CheckInReceipt.prepare(owner,sessionId)==null)return false;synchronized(lock){
   if(closed||active!=null)return false;
   // A first attempt may fail before send() arms the port (e.g. not connected).
   printer.arm(sessionId);
   return printer.authorizeRetry(sessionId);
  }}
 }
 public void close(){
  synchronized(lock){if(closed)return;closed=true;if(active!=null)active.cancelled=true;printer.invalidate();}
  worker.shutdown();try{printer.close();}catch(Exception|LinkageError ignored){}
 }
}
