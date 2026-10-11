package com.triathlon.prep.core;

import java.util.*;

/** Printing lifecycle only; ACCEPTED is an adapter acknowledgement, never proof of paper output. */
public final class ReceiptPrintController implements AutoCloseable {
 public enum Status { IDLE, SENDING, ACCEPTED, SIMULATED, FAILED, CANCELLED }
 public enum Failure { NONE, INELIGIBLE, WRONG_MODE, NOT_CONNECTED, PRINTER_ERROR, TIMEOUT, STALE_SESSION, CANCELLED }
 public interface Reply { void accepted();void failed(Failure reason); }
 public interface Transport {
  Verification.Mode mode();
  void print(String jobId,List<String> lines,Reply reply);
  void cancel(String jobId);
 }
 public static final class Snapshot {
  public final String jobId,sessionId;public final Status status;public final Failure failure;public final boolean simulation;
  public final CheckInReceipt.Document document;
  private Snapshot(String jobId,String sessionId,Status status,Failure failure,boolean simulation,CheckInReceipt.Document document){this.jobId=jobId;this.sessionId=sessionId;this.status=status;this.failure=failure;this.simulation=simulation;this.document=document;}
 }
 private final Verification flow;private final Transport transport;private final Verification.Clock clock;private final long timeoutMillis;
 private final java.util.function.Supplier<String> heading;
 private String jobId="";private CheckInReceipt.Document document;private CheckInReceipt.Layout layout;
 private Status status=Status.IDLE;private Failure failure=Failure.NONE;private long deadline;private boolean closed;
 public ReceiptPrintController(Verification flow,Transport transport,Verification.Clock clock,long timeoutMillis){
  this(flow,transport,clock,timeoutMillis,()->"");
 }
 public ReceiptPrintController(Verification flow,Transport transport,Verification.Clock clock,long timeoutMillis,java.util.function.Supplier<String> heading){
  if(flow==null||transport==null||clock==null||heading==null||timeoutMillis<1||timeoutMillis>300000)throw new IllegalArgumentException("Explicit printing controller configuration required");
  this.flow=flow;this.transport=transport;this.clock=clock;this.timeoutMillis=timeoutMillis;this.heading=heading;
 }
 /** A retry is another explicit submission of the same still-current document with a fresh job ID. */
 public synchronized String submit(CheckInReceipt.Document candidate,CheckInReceipt.Layout metrics){
  if(closed)throw new IllegalStateException("Printer controller is closed");
  tick();if(status==Status.SENDING)throw new IllegalStateException("A print request is already active");
  synchronized(flow){
   if(candidate==null||metrics==null)throw new IllegalStateException("Current receipt required");
   candidate=CheckInReceipt.withEventHeading(candidate,heading.get());
   if(transport.mode()!=(candidate.simulation?Verification.Mode.TEST:Verification.Mode.LIVE))throw new IllegalStateException("Receipt and printer modes differ");
   List<String> lines=CheckInReceipt.render(flow,candidate,metrics);
   if(!candidate.simulation&&!CheckInReceipt.canPrint(flow,candidate))throw new IllegalStateException("Receipt no longer eligible");
   document=candidate;layout=metrics;jobId=UUID.randomUUID().toString();status=Status.SENDING;failure=Failure.NONE;deadline=clock.elapsedMillis()+timeoutMillis;
   final String id=jobId;
   try{transport.print(id,lines,new Reply(){
    public void accepted(){synchronized(ReceiptPrintController.this){if(!current(id))return;status=document.simulation?Status.SIMULATED:Status.ACCEPTED;failure=Failure.NONE;}}
    public void failed(Failure reason){synchronized(ReceiptPrintController.this){if(!current(id))return;finish(Status.FAILED,reason==null||reason==Failure.NONE?Failure.PRINTER_ERROR:reason);}}
   });}catch(RuntimeException|LinkageError ex){if(current(id))finish(Status.FAILED,Failure.PRINTER_ERROR);}
   return id;
  }
 }
 private boolean current(String id){if(!jobId.equals(id)||status!=Status.SENDING)return false;tick();return status==Status.SENDING;}
 public synchronized void cancel(){if(status==Status.SENDING)finish(Status.CANCELLED,Failure.CANCELLED);}
 public synchronized void tick(){
  if(status!=Status.SENDING)return;
  try{CheckInReceipt.render(flow,document,layout);}catch(IllegalStateException ex){finish(Status.CANCELLED,Failure.STALE_SESSION);return;}
  if(clock.elapsedMillis()>=deadline)finish(Status.FAILED,Failure.TIMEOUT);
 }
 private void finish(Status end,Failure reason){
  status=end;failure=reason;
  try{transport.cancel(jobId);}catch(RuntimeException|LinkageError ignored){/* State invalidated before driver cleanup. */}
 }
 public synchronized Snapshot snapshot(){tick();return new Snapshot(jobId,document==null?"":document.sessionId,status,failure,document!=null&&document.simulation,document);}
 @Override public synchronized void close(){if(closed)return;closed=true;cancel();}
}
