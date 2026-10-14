package com.triathlon.prep.nextday.baidu;

import android.content.Context;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.core.nextday.*;
import java.io.IOException;
import java.util.*;

/** Synchronous adapter on the Host's one SDK queue. Getters never construct SDK classes. */
public final class BaiduFaceEngine implements FaceEngine {
 public enum Kind { REAL, SIMULATION }
 public interface SdkPort extends AutoCloseable {
  void initialize()throws IOException;void clear()throws IOException;void push(int id,byte[] feature)throws IOException;
  int size()throws IOException;Evaluation evaluate(CapturePort.Frame frame,boolean live)throws IOException;
  List<Candidate> search(byte[] feature,int count,double threshold)throws IOException;void close()throws IOException;
 }
 public interface PortFactory {SdkPort create()throws IOException;}
 private final FaceLibrary.Scope scope;private final RuntimeGate gate;private final PortFactory factory;private final Kind kind;
 private volatile boolean closed,initialized,failed,releasePending;private SdkPort sdk;private volatile Thread owner;private boolean indexReady;private int people;
 private IOException releaseFailure;
 public BaiduFaceEngine(Context context,FaceLibrary.Scope scope,RuntimeGate gate){this(context,scope,gate,null);}
 public BaiduFaceEngine(Context context,FaceLibrary.Scope scope,RuntimeGate gate,BaiduV9Configuration configuration){
  this(scope,gate,configuration==null?null:()->new BaiduV9Bridge(context,configuration),Kind.REAL);
  if(configuration!=null&&!scope.modelFingerprint.equals(configuration.modelFingerprint))throw new IllegalArgumentException("SDK model fingerprint mismatch");
 }
 public BaiduFaceEngine(FaceLibrary.Scope scope,RuntimeGate gate,PortFactory factory,Kind kind){
  if(scope==null||kind==null)throw new IllegalArgumentException("Explicit scope required");this.scope=scope;this.gate=gate;this.factory=factory;this.kind=kind;
 }
 public Verification.Mode mode(){return scope.mode;}public FaceLibrary.Scope scope(){return scope;}
 public Parameters parameters(){return gate==null?null:gate.parameters();}
 public Readiness readiness(){
  if(closed)return Readiness.CLOSED;
  if(releasePending)return Readiness.FAILED;
  try{
   if(gate==null||factory==null||(kind==Kind.REAL)!=(mode()==Verification.Mode.LIVE)||!"LIVE_PHOTO".equals(scope.featureType))return Readiness.UNCONFIGURED;
   if(!gate.nativeInitializationAllowed()||!gate.licenceVerified())return Readiness.UNVERIFIED_LICENSE;
   Parameters p=parameters();if(p==null||!p.calibrated)return Readiness.UNVERIFIED_PARAMETERS;
   return failed?Readiness.FAILED:initialized?Readiness.READY:Readiness.UNCONFIGURED;
  }catch(RuntimeException e){return Readiness.UNCONFIGURED;}
 }
 private Parameters allowed(boolean needInitialized)throws IOException{
  if(closed)throw new IOException("BAIDU_CLOSED");
  if(releasePending)throw new IOException("BAIDU_RELEASE_PENDING",releaseFailure);
  if(owner!=null&&Thread.currentThread()!=owner)throw new IOException("BAIDU_SERIAL_OWNER_REQUIRED");
  if(gate==null||factory==null||(kind==Kind.REAL)!=(mode()==Verification.Mode.LIVE)||!"LIVE_PHOTO".equals(scope.featureType))throw new IOException("BAIDU_UNCONFIGURED");
  if(!gate.nativeInitializationAllowed()||!gate.licenceVerified())throw new IOException("BAIDU_UNVERIFIED_LICENSE");
  Parameters p=parameters();if(p==null||!p.calibrated)throw new IOException("BAIDU_UNVERIFIED_PARAMETERS");
  if(needInitialized&&!initialized)throw new IOException("BAIDU_NOT_INITIALIZED");return p;
 }
 private void unchanged(Parameters previous)throws IOException{
  Parameters p=allowed(true);
  if(p.revision!=previous.revision||p.matchThreshold!=previous.matchThreshold||p.minimumMargin!=previous.minimumMargin||p.maximumFrameAgeMillis!=previous.maximumFrameAgeMillis)throw new IOException("BAIDU_PARAMETERS_CHANGED");
 }
 private interface PortCall<T>{T run()throws IOException;}
 private <T>T port(PortCall<T> call)throws IOException{try{return call.run();}catch(RuntimeException|LinkageError e){throw new IOException("BAIDU_SDK_FAILURE");}}
 public void initialize()throws IOException{
  Parameters p;synchronized(this){p=allowed(false);if(initialized)return;owner=Thread.currentThread();}
  try{sdk=factory.create();if(sdk==null)throw new IOException("BAIDU_FACTORY_UNAVAILABLE");sdk.initialize();initialized=true;failed=false;unchanged(p);}
  catch(Exception|LinkageError e){initialized=false;failed=true;IOException failure=new IOException("BAIDU_INITIALIZATION_FAILED",e);SdkPort old=sdk;if(old!=null)try{old.close();sdk=null;}catch(Exception|LinkageError cleanup){releasePending=true;failure.addSuppressed(new IOException("BAIDU_RELEASE_PENDING",cleanup));releaseFailure=failure;}throw failure;}
 }
 public void clear()throws IOException{
  Parameters p=allowed(true);indexReady=false;people=0;port(()->{sdk.clear();return null;});if(port(sdk::size)!=0)throw new IOException("BAIDU_INDEX_CLEAR_FAILED");unchanged(p);
 }
 public void rebuild(FaceLibrary.Snapshot snapshot)throws IOException{
  if(snapshot==null||!scope.equals(snapshot.scope))throw new IOException("BAIDU_INDEX_SCOPE_MISMATCH");
  Parameters p=allowed(true);indexReady=false;people=0;
  port(()->{sdk.clear();return null;});if(port(sdk::size)!=0)throw new IOException("BAIDU_INDEX_CLEAR_FAILED");
  for(FaceLibrary.Entry entry:snapshot.entries){unchanged(p);byte[] bytes=entry.feature();try{port(()->{sdk.push(entry.id,bytes);return null;});}finally{Arrays.fill(bytes,(byte)0);}}
  if(port(sdk::size)!=snapshot.entries.size())throw new IOException("BAIDU_INDEX_COUNT_MISMATCH");unchanged(p);people=snapshot.entries.size();indexReady=true;
 }
 public Evaluation evaluate(CapturePort.Frame frame,boolean live)throws IOException{
  Parameters p=allowed(true);if(frame==null)throw new IOException("BAIDU_FRAME_MISSING");Evaluation evaluation=null;
  try{evaluation=sdk.evaluate(frame,live);if(evaluation==null)throw new IOException("BAIDU_EMPTY_EVALUATION");unchanged(p);
   if(live&&evaluation.liveness==StepResult.NOT_REQUIRED)throw new IOException("BAIDU_LIVENESS_REQUIRED");return evaluation;
  }catch(Exception|LinkageError e){if(evaluation!=null)evaluation.close();throw new IOException("BAIDU_EVALUATION_FAILED");}
 }
 public List<Candidate> search(byte[] feature,int count)throws IOException{
  Parameters p=allowed(true);if(!indexReady)throw new IOException("BAIDU_INDEX_NOT_READY");
  if(feature==null||feature.length!=512||count<2||count>256)throw new IOException("BAIDU_QUERY_INVALID");
  if(people==0)return Collections.emptyList();byte[] owned=feature.clone();
  double floor=Math.max(0,p.matchThreshold-p.minimumMargin);
  try{List<Candidate> raw=port(()->sdk.search(owned,count,floor));unchanged(p);if(raw==null||raw.size()>count)throw new IOException("BAIDU_QUERY_INVALID");
   Set<Integer> ids=new HashSet<>();List<Candidate> out=new ArrayList<>();for(Candidate candidate:raw){if(candidate==null||!ids.add(candidate.id))throw new IOException("BAIDU_QUERY_INVALID");out.add(candidate);}return Collections.unmodifiableList(out);
  }finally{Arrays.fill(owned,(byte)0);}
 }
 public synchronized void close()throws IOException{
  if(owner!=null&&owner!=Thread.currentThread())throw new IOException("BAIDU_SERIAL_OWNER_REQUIRED");
  if(closed)return;initialized=false;indexReady=false;releasePending=true;SdkPort old=sdk;
  try{if(old!=null)port(()->{old.close();return null;});sdk=null;releaseFailure=null;releasePending=false;closed=true;}
  catch(IOException e){failed=true;if(releaseFailure==null)releaseFailure=e;else if(e!=releaseFailure)e.addSuppressed(releaseFailure);throw e;}
 }
}
