package com.triathlon.prep.nextday.host;
import com.triathlon.prep.core.*;import com.triathlon.prep.core.nextday.*;import com.triathlon.prep.nextday.records.NextDayRecordStore;
import java.io.IOException;import java.util.function.LongSupplier;
/** Host-owned module assembly. A constructor never initializes SDK or indexes. */
public final class NextDayModule implements AutoCloseable {
 public final FaceLibrary library;public final FaceEngine engine;public final FaceEnrollment enrollment;public final FaceVerificationWorkflow workflow;
 private final FaceLibrary.Store store;public final CapturePort capture;private final FaceEngine.RuntimeGate gate;private boolean closed,prepared;
 private String preparationNotice="许可、模型或参数未确认。";
 public void preparationNotice(String safeNotice){preparationNotice=safeNotice==null?"准备状态待核。":safeNotice;}
 public String preparationNotice(){return preparationNotice;}
 public NextDayModule(FaceLibrary.Scope scope,FaceLibrary.Store store,FaceEngine engine,CapturePort capture,NextDayRecordStore records,QualifiedSceneSlot scenes,VerificationModeSession authority,Verification.Clock clock,FaceEngine.RuntimeGate gate,LongSupplier surface){
  if(scope==null||store==null||engine==null||!scope.equals(engine.scope())||capture==null||records==null||scenes==null||authority==null||clock==null||gate==null||surface==null)throw new IllegalArgumentException("Explicit module scope and ports required");
  this.store=store;this.engine=engine;this.capture=capture;this.gate=gate;library=new FaceLibrary(scope,store,engine,authority);enrollment=new FaceEnrollment(library,engine,scenes,clock);workflow=new FaceVerificationWorkflow(authority,capture,engine,library,records,clock,gate,surface);
 }
 public boolean prepare()throws IOException{
  if(closed)throw new IOException("Module closed");
  FaceEngine.Parameters parameters=gate.parameters();
  if(!gate.nativeInitializationAllowed()||!gate.licenceVerified()||parameters==null||!parameters.calibrated)return false;
  if(prepared&&engine.readiness()==FaceEngine.Readiness.READY&&(library.status()==FaceLibrary.Status.EMPTY||library.status()==FaceLibrary.Status.READY))return true;
  engine.initialize();if(engine.readiness()!=FaceEngine.Readiness.READY)return false;
  library.load();prepared=library.status()==FaceLibrary.Status.EMPTY||library.status()==FaceLibrary.Status.READY;return prepared;
 }
 public void cancel(String operationId){workflow.cancel(operationId);}
 public void close()throws IOException{
  if(closed)return;IOException failure=null;
  try{capture.close();}catch(IOException|RuntimeException e){failure=new IOException("Capture release failed",e);}
  try{engine.close();}catch(IOException|RuntimeException e){if(failure==null)failure=new IOException("Engine release failed",e);else failure.addSuppressed(e);}
  if(failure!=null)throw failure;
  if(store instanceof AutoCloseable)try{((AutoCloseable)store).close();}catch(Exception e){throw new IOException("Storage release failed",e);}
  closed=true;
 }
}
