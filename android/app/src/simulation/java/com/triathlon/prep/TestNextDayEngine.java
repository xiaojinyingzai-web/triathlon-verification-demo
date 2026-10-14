package com.triathlon.prep;
import com.triathlon.prep.core.Verification;import com.triathlon.prep.core.nextday.*;import java.io.IOException;import java.util.*;
/** TEST-only deterministic byte markers. No image/model/native or biometric meaning. */
final class TestNextDayEngine implements FaceEngine {
 private final FaceLibrary.Scope scope;private final Map<Integer,byte[]> markers=new TreeMap<>();private boolean ready,closed;
 TestNextDayEngine(FaceLibrary.Scope scope){if(scope==null||scope.mode!=Verification.Mode.TEST)throw new IllegalArgumentException("TEST scope required");this.scope=scope;}
 public Verification.Mode mode(){return Verification.Mode.TEST;}public FaceLibrary.Scope scope(){return scope;}
 public Parameters parameters(){return new Parameters(1,1,.5,15000,true);}public Readiness readiness(){return closed?Readiness.CLOSED:ready?Readiness.READY:Readiness.UNCONFIGURED;}
 public void initialize()throws IOException{usable(false);ready=true;}
 private void usable(boolean initialized)throws IOException{if(closed||initialized&&!ready)throw new IOException("TEST marker engine unavailable");}
 public void clear()throws IOException{usable(true);for(byte[] value:markers.values())Arrays.fill(value,(byte)0);markers.clear();}
 public void rebuild(FaceLibrary.Snapshot snapshot)throws IOException{usable(true);if(snapshot==null||!scope.equals(snapshot.scope))throw new IOException("TEST marker scope mismatch");clear();for(FaceLibrary.Entry entry:snapshot.entries)markers.put(entry.id,entry.feature());}
 public Evaluation evaluate(CapturePort.Frame frame,boolean live)throws IOException{
  usable(true);byte[] bytes=frame.image();try{if(bytes.length!=1||frame.type!=CapturePort.ImageType.JPEG||frame.width!=1||frame.height!=1)throw new IOException("Only explicit TEST marker frames accepted");byte[] feature=new byte[512];feature[0]=bytes[0];try{return new Evaluation(StepResult.PASSED,live?StepResult.PASSED:StepResult.NOT_REQUIRED,feature);}finally{Arrays.fill(feature,(byte)0);}}finally{Arrays.fill(bytes,(byte)0);}
 }
 public List<Candidate> search(byte[] feature,int count)throws IOException{usable(true);if(feature==null||feature.length!=512||count<2)throw new IOException("TEST marker query invalid");List<Candidate> found=new ArrayList<>();for(Map.Entry<Integer,byte[]> entry:markers.entrySet())found.add(new Candidate(entry.getKey(),Arrays.equals(feature,entry.getValue())?2:0));found.sort((a,b)->Double.compare(b.score,a.score));return new ArrayList<>(found.subList(0,Math.min(count,found.size())));}
 public void close(){for(byte[] bytes:markers.values())Arrays.fill(bytes,(byte)0);markers.clear();closed=true;ready=false;}
}
