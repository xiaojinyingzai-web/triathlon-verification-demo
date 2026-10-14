package com.triathlon.prep.core;

import java.util.*;

/** Printing lifecycle only; ACCEPTED is an adapter acknowledgement, never proof of paper output. */
public final class ReceiptPrintController implements AutoCloseable {
 public enum Status { IDLE, SENDING, ACCEPTED, SIMULATED, FAILED, CANCELLED }
 public enum Failure { NONE, INELIGIBLE, WRONG_MODE, NOT_CONNECTED, PRINTER_ERROR, TIMEOUT, STALE_SESSION, CANCELLED }
 public interface Reply { void accepted();void failed(Failure reason); }
 public static final class Raster {
  public final int width,height,paperHeight;private final byte[] png;
  public Raster(byte[] png,int width,int height,int paperHeight){
   if(png==null||png.length<33||png.length>8*1024*1024||width<1||width>2048||height<1||height>8192||paperHeight<1||paperHeight>height||(long)width*height>4000000)throw new IllegalArgumentException("Bounded PNG dimensions required");
   byte[] signature={(byte)137,80,78,71,13,10,26,10};for(int i=0;i<8;i++)if(png[i]!=signature[i])throw new IllegalArgumentException("PNG required");
   if(png[12]!='I'||png[13]!='H'||png[14]!='D'||png[15]!='R'||integer(png,16)!=width||integer(png,20)!=height)throw new IllegalArgumentException("PNG dimension mismatch");
   this.png=png.clone();this.width=width;this.height=height;this.paperHeight=paperHeight;
  }
  private static int integer(byte[] p,int at){return (p[at]&255)<<24|(p[at+1]&255)<<16|(p[at+2]&255)<<8|p[at+3]&255;}
  public byte[] pngBytes(){return png.clone();}
 }
 public interface GraphicRenderer { Raster render(CheckInReceipt.Document document,CheckInReceipt.Layout layout); }
 public interface Transport {
  Verification.Mode mode();
  void print(String jobId,List<String> lines,Reply reply);
  /** Explicit image capability. Never masquerade a text fallback as the graphical receipt. */
  default void printImage(String jobId,Raster image,Reply reply){reply.failed(Failure.NOT_CONNECTED);}
  default void printManualImage(String jobId,Raster image,ManualPrintAuthorization authority,Reply reply){reply.failed(Failure.NOT_CONNECTED);}
  default boolean authorizeManualRetry(ManualPrintAuthorization authority,boolean acknowledged){return acknowledged&&authority!=null&&authority.current()&&mode()==Verification.Mode.TEST;}
  void cancel(String jobId);
  /** Only an explicit operator request may renew an attempted current-session send. */
  default boolean authorizeRetry(String sessionId,boolean possibleDuplicateAcknowledged) {
   return possibleDuplicateAcknowledged && mode()==Verification.Mode.TEST;
  }
 }
 public static final class Snapshot {
  public final String jobId,sessionId;public final Status status;public final Failure failure;public final boolean simulation;
  public final CheckInReceipt.Document document;
  public final Raster raster;
  private Snapshot(String jobId,String sessionId,Status status,Failure failure,boolean simulation,CheckInReceipt.Document document,Raster raster){this.jobId=jobId;this.sessionId=sessionId;this.status=status;this.failure=failure;this.simulation=simulation;this.document=document;this.raster=raster;}
 }
 private final Verification flow;private final Transport transport;private final Verification.Clock clock;private final long timeoutMillis;
 private final java.util.function.Supplier<String> heading;
 private volatile java.util.function.BooleanSupplier printPermission=()->true;
 private GraphicRenderer graphicRenderer;private java.util.function.Supplier<ReceiptTemplate> templates;
 private String jobId="";private CheckInReceipt.Document document;private CheckInReceipt.Layout layout;
 private Raster raster;
 private ManualPrintAuthorization manualAuthority;
 private Status status=Status.IDLE;private Failure failure=Failure.NONE;private long deadline;private boolean closed;
 public ReceiptPrintController(Verification flow,Transport transport,Verification.Clock clock,long timeoutMillis){
  this(flow,transport,clock,timeoutMillis,()->"");
 }
 public ReceiptPrintController(Verification flow,Transport transport,Verification.Clock clock,long timeoutMillis,java.util.function.Supplier<String> heading){
  if(flow==null||transport==null||clock==null||heading==null||timeoutMillis<1||timeoutMillis>300000)throw new IllegalArgumentException("Explicit printing controller configuration required");
  this.flow=flow;this.transport=transport;this.clock=clock;this.timeoutMillis=timeoutMillis;this.heading=heading;
 }
 /** Bind the runtime policy; legacy independent controllers retain their existing permission. */
 public void setPrintPermission(java.util.function.BooleanSupplier permission){
  if(permission==null)throw new IllegalArgumentException("Print permission required");printPermission=permission;
 }
 public synchronized void setGraphicRenderer(GraphicRenderer renderer){if(renderer==null||status==Status.SENDING)throw new IllegalStateException("Renderer required before an active job");graphicRenderer=renderer;}
 public synchronized void setTemplateSupplier(java.util.function.Supplier<ReceiptTemplate> supplier){if(supplier==null||status==Status.SENDING)throw new IllegalStateException("Templates required before an active job");templates=supplier;}
 private boolean printingAllowed(){try{return printPermission.getAsBoolean();}catch(RuntimeException|LinkageError ex){return false;}}
 private String duplicateGrant="";
 public synchronized boolean authorizeDuplicatePrint(String sessionId,boolean confirmed){
  if(closed||!confirmed||!printingAllowed()||status==Status.SENDING)return false;
  synchronized(flow){Verification.Snapshot s=flow.snapshot();if(!s.sessionId.equals(sessionId)||s.saveState!=Verification.SaveState.SAVED||s.checkIn!=CheckIn.State.DUPLICATE||!CheckIn.trustedSuccess(s.result))return false;duplicateGrant=sessionId;return true;}
 }
 /** A retry is another explicit submission of the same still-current document with a fresh job ID. */
 public synchronized boolean authorizeRetry(String sessionId,boolean possibleDuplicateAcknowledged){
  tick();
  if(closed||!printingAllowed()||!possibleDuplicateAcknowledged||document==null||!document.sessionId.equals(sessionId)
    ||status==Status.IDLE||status==Status.SENDING)return false;
  synchronized(flow){
   try{
    if(manualAuthority!=null){if(!manualAuthority.current()||!manualIdle())return false;}
    else{CheckInReceipt.render(flow,document,layout);if(!document.simulation&&!CheckInReceipt.canPrint(flow,document))return false;}
    return printingAllowed()&&(manualAuthority!=null?transport.authorizeManualRetry(manualAuthority,true):transport.authorizeRetry(sessionId,true));
   }catch(RuntimeException|LinkageError ex){return false;}
  }
 }
 public synchronized String submit(CheckInReceipt.Document candidate,CheckInReceipt.Layout metrics){
  if(closed)throw new IllegalStateException("Printer controller is closed");
  if(!printingAllowed())throw new IllegalStateException("Printing is disabled or setting unconfirmed");
  tick();if(status==Status.SENDING)throw new IllegalStateException("A print request is already active");
  synchronized(flow){
   if(candidate==null||metrics==null)throw new IllegalStateException("Current receipt required");
   Verification.Snapshot registered=flow.snapshot();boolean duplicateConfirmed=registered.sessionId.equals(duplicateGrant);duplicateGrant="";
   if(registered.checkIn==CheckIn.State.UNLINKABLE||registered.checkIn==CheckIn.State.DUPLICATE&&!duplicateConfirmed)throw new IllegalStateException("Duplicate receipt requires explicit current confirmation");
   candidate=templates==null?CheckInReceipt.withEventHeading(candidate,heading.get()):CheckInReceipt.withTemplate(candidate,templates.get());
   if(transport.mode()!=(candidate.simulation?Verification.Mode.TEST:Verification.Mode.LIVE))throw new IllegalStateException("Receipt and printer modes differ");
   List<String> lines=CheckInReceipt.render(flow,candidate,metrics);
   if(!candidate.simulation&&!CheckInReceipt.canPrint(flow,candidate))throw new IllegalStateException("Receipt no longer eligible");
   if(!printingAllowed())throw new IllegalStateException("Printing is disabled or setting unconfirmed");
   document=candidate;layout=metrics;raster=null;manualAuthority=null;jobId=UUID.randomUUID().toString();status=Status.SENDING;failure=Failure.NONE;deadline=clock.elapsedMillis()+timeoutMillis;
   final String id=jobId;
   Reply reply=new Reply(){
    public void accepted(){synchronized(ReceiptPrintController.this){if(!current(id))return;status=document.simulation?Status.SIMULATED:Status.ACCEPTED;failure=Failure.NONE;}}
    public void failed(Failure reason){synchronized(ReceiptPrintController.this){if(!current(id))return;finish(Status.FAILED,reason==null||reason==Failure.NONE?Failure.PRINTER_ERROR:reason);}}
   };
   try{
    if(graphicRenderer==null)transport.print(id,lines,reply);
    else{Raster image=graphicRenderer.render(document,metrics);if(image==null||image.width!=metrics.printableDots||(document.simulation?image.paperHeight>=image.height:image.paperHeight!=image.height))throw new IllegalArgumentException("Image must match configured width and mode watermark bounds");
     if(!printingAllowed())throw new IllegalStateException("Printing disabled before image dispatch");raster=image;transport.printImage(id,image,reply);}
   }catch(RuntimeException|LinkageError|OutOfMemoryError ex){if(current(id))finish(Status.FAILED,Failure.PRINTER_ERROR);}
   return id;
  }
 }
 private boolean manualIdle(){Verification.Snapshot s=flow.snapshot();return s.stage==Verification.Stage.IDLE||s.stage==Verification.Stage.FINISHED&&s.saveState==Verification.SaveState.SAVED;}
 public synchronized boolean renewManualPrint(ManualPrintAuthorization authority,boolean acknowledged){tick();return !closed&&status!=Status.SENDING&&printingAllowed()&&acknowledged&&authority!=null&&authority.current()&&manualIdle()&&transport.mode()==authority.mode&&transport.authorizeManualRetry(authority,true);}
 public synchronized String submitManual(ManualPrintAuthorization authority,CheckInReceipt.Layout metrics){
  if(closed||!printingAllowed()||authority==null||metrics==null||graphicRenderer==null)throw new IllegalStateException("Manual printing parameters/permission required");tick();if(status==Status.SENDING)throw new IllegalStateException("Print request active");
  synchronized(flow){if(!manualIdle()||flow.snapshot().mode!=authority.mode||transport.mode()!=authority.mode||!authority.current())throw new IllegalStateException("Current committed manual grant required");
   ReceiptTemplate template=templates==null?ReceiptTemplate.defaults().withText(ReceiptTemplate.TextKey.EVENT_HEADING,heading.get()):templates.get();document=CheckInReceipt.manual(authority,template);manualAuthority=authority;layout=metrics;raster=null;jobId=UUID.randomUUID().toString();status=Status.SENDING;failure=Failure.NONE;deadline=clock.elapsedMillis()+timeoutMillis;final String id=jobId;
   Reply reply=new Reply(){public void accepted(){synchronized(ReceiptPrintController.this){if(!current(id))return;status=document.simulation?Status.SIMULATED:Status.ACCEPTED;failure=Failure.NONE;}}public void failed(Failure reason){synchronized(ReceiptPrintController.this){if(current(id))finish(Status.FAILED,reason==null||reason==Failure.NONE?Failure.PRINTER_ERROR:reason);}}};
   try{Raster image=graphicRenderer.render(document,metrics);if(image==null||image.width!=metrics.printableDots||(document.simulation?image.paperHeight>=image.height:image.paperHeight!=image.height)||!authority.current()||!printingAllowed())throw new IllegalStateException("Manual image/authorization no longer current");raster=image;transport.printManualImage(id,image,authority,reply);}catch(RuntimeException|LinkageError|OutOfMemoryError error){if(current(id))finish(Status.FAILED,Failure.PRINTER_ERROR);}return id;
  }
 }
 private boolean current(String id){if(!jobId.equals(id)||status!=Status.SENDING)return false;tick();return status==Status.SENDING;}
 public synchronized void cancel(){duplicateGrant="";if(status==Status.SENDING)finish(Status.CANCELLED,Failure.CANCELLED);}
 public synchronized void tick(){
  if(status!=Status.SENDING)return;
  if(manualAuthority!=null){if(!manualAuthority.current()||!manualIdle()){finish(Status.CANCELLED,Failure.STALE_SESSION);return;}}
  else try{CheckInReceipt.render(flow,document,layout);}catch(IllegalStateException ex){finish(Status.CANCELLED,Failure.STALE_SESSION);return;}
  if(clock.elapsedMillis()>=deadline)finish(Status.FAILED,Failure.TIMEOUT);
 }
 private void finish(Status end,Failure reason){
  status=end;failure=reason;
  try{transport.cancel(jobId);}catch(RuntimeException|LinkageError ignored){/* State invalidated before driver cleanup. */}
 }
 public synchronized Snapshot snapshot(){tick();return new Snapshot(jobId,document==null?"":document.sessionId,status,failure,document!=null&&document.simulation,document,raster);}
 @Override public synchronized void close(){if(closed)return;closed=true;cancel();}
}
