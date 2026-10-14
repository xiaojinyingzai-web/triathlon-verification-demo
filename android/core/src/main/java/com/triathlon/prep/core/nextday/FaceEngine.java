package com.triathlon.prep.core.nextday;
import com.triathlon.prep.core.Verification;import java.io.IOException;import java.util.*;

/** Single-queue SDK adapter; getters/constructor must never initialize native code. */
public interface FaceEngine extends FaceLibrary.Index,AutoCloseable {
 Verification.Mode mode();FaceLibrary.Scope scope();Readiness readiness();Parameters parameters();
 void initialize()throws IOException;
 Evaluation evaluate(CapturePort.Frame frame,boolean requireLiveness)throws IOException;
 List<Candidate> search(byte[] feature,int topCount)throws IOException;
 void close()throws IOException;
 enum Readiness { UNCONFIGURED, UNVERIFIED_LICENSE, UNVERIFIED_PARAMETERS, READY, FAILED, CLOSED }
 enum StepResult { PASSED, FAILED, UNKNOWN, NOT_REQUIRED }
 /** This supplier is Host-owned evidence/configuration, never a UI checkbox or activation-code string. */
 interface RuntimeGate {boolean nativeInitializationAllowed();boolean licenceVerified();Parameters parameters();}
 final class Parameters {
  public final long revision,maximumFrameAgeMillis;public final double matchThreshold,minimumMargin;public final boolean calibrated;
  public Parameters(long revision,double matchThreshold,double minimumMargin,long maximumFrameAgeMillis,boolean calibrated){if(revision<1||!Double.isFinite(matchThreshold)||matchThreshold<0||!Double.isFinite(minimumMargin)||minimumMargin<0||maximumFrameAgeMillis<1||maximumFrameAgeMillis>60000)throw new IllegalArgumentException("Invalid model parameters");this.revision=revision;this.matchThreshold=matchThreshold;this.minimumMargin=minimumMargin;this.maximumFrameAgeMillis=maximumFrameAgeMillis;this.calibrated=calibrated;}
 }
 final class Evaluation implements AutoCloseable {
  public final StepResult quality,liveness;private byte[] feature;
  public Evaluation(StepResult quality,StepResult liveness,byte[] feature){if(quality==null||liveness==null||feature!=null&&(feature.length!=512||quality!=StepResult.PASSED||(liveness!=StepResult.PASSED&&liveness!=StepResult.NOT_REQUIRED)))throw new IllegalArgumentException("Invalid evaluated feature authority");this.quality=quality;this.liveness=liveness;this.feature=feature==null?null:feature.clone();}
  public synchronized byte[] feature(){return feature==null?null:feature.clone();}
  @Override public synchronized void close(){if(feature!=null){Arrays.fill(feature,(byte)0);feature=null;}}
 }
 final class Candidate {
  public final int id;public final double score;
  public Candidate(int id,double score){if(id<1||!Double.isFinite(score)||score<0)throw new IllegalArgumentException("Invalid search candidate");this.id=id;this.score=score;}
 }
}
