package com.triathlon.prep.core.nextday.decision;

import com.triathlon.prep.core.nextday.FaceEngine;
import com.triathlon.prep.core.nextday.FaceLibrary;
import java.util.*;

/** Pure search-evidence policy. Host must separately validate current operation and frame freshness. */
public final class FaceDecision {
 private FaceDecision() {}
 public enum Reason { PASSED, INVALID_INPUT, UNVERIFIED_LICENSE, ENGINE_NOT_READY, UNCALIBRATED_PARAMETERS, STALE_PARAMETERS, LIBRARY_NOT_READY, EMPTY_LIBRARY, SCOPE_MISMATCH, STALE_LIBRARY, QUALITY_REJECTED, LIVENESS_REJECTED, NO_CANDIDATES, INVALID_CANDIDATE, DUPLICATE_CANDIDATE, UNKNOWN_PERSON, INCOMPLETE_TOP_TWO, LOW_SCORE, AMBIGUOUS }
 public static final class Input {
  public final FaceLibrary.Scope expectedScope;
  public final FaceLibrary.Status libraryStatus;
  public final FaceLibrary.Snapshot currentLibrary;
  public final String searchedLibraryRevision;
  public final FaceEngine.Readiness engineReadiness;
  public final boolean licenceVerified;
  public final FaceEngine.Parameters searchedParameters,currentParameters;
  public final FaceEngine.StepResult quality,liveness;
  public final List<FaceEngine.Candidate> candidates;
  public Input(FaceLibrary.Scope expectedScope,FaceLibrary.Status libraryStatus,FaceLibrary.Snapshot currentLibrary,String searchedLibraryRevision,FaceEngine.Readiness engineReadiness,boolean licenceVerified,FaceEngine.Parameters searchedParameters,FaceEngine.Parameters currentParameters,FaceEngine.StepResult quality,FaceEngine.StepResult liveness,List<FaceEngine.Candidate> candidates){
   this.expectedScope=expectedScope;this.libraryStatus=libraryStatus;this.currentLibrary=currentLibrary;this.searchedLibraryRevision=searchedLibraryRevision;this.engineReadiness=engineReadiness;this.licenceVerified=licenceVerified;this.searchedParameters=searchedParameters;this.currentParameters=currentParameters;this.quality=quality;this.liveness=liveness;this.candidates=candidates==null?null:Collections.unmodifiableList(new ArrayList<>(candidates));
  }
 }
 /** Personnel mapping only; deliberately carries no feature or photo. */
 public static final class Person {
  public final int id;public final String bib,name,group,batchId,libraryRevision;public final long parameterRevision;
  private Person(FaceLibrary.Entry entry,FaceLibrary.Snapshot library,long parameterRevision){id=entry.id;bib=entry.bib;name=entry.name;group=entry.group;batchId=library.scope.batchId;libraryRevision=library.revision;this.parameterRevision=parameterRevision;}
 }
 public static final class Result {
  public final Reason reason;public final Person person;
  private Result(Reason reason,Person person){this.reason=reason;this.person=person;}
  public boolean passed(){return reason==Reason.PASSED;}
 }
 private static Result reject(Reason reason){return new Result(reason,null);}
 /**
  * Checks supplied evidence only: the caller owns trustworthy evidence capture and final freshness.
  * A missing runner-up in a multi-person library is conservatively incomplete, including when an
  * SDK prefilter suppressed that runner-up. It is not evidence of no match or measured accuracy.
  */
 public static Result decide(Input input){
  if(input==null || input.expectedScope==null || input.libraryStatus==null || input.currentLibrary==null
    || input.searchedLibraryRevision==null || input.engineReadiness==null || input.searchedParameters==null
    || input.currentParameters==null || input.quality==null || input.liveness==null || input.candidates==null)
   return reject(Reason.INVALID_INPUT);
  if(!input.licenceVerified)return reject(Reason.UNVERIFIED_LICENSE);
  if(input.engineReadiness!=FaceEngine.Readiness.READY)return reject(Reason.ENGINE_NOT_READY);
  FaceEngine.Parameters searched=input.searchedParameters,current=input.currentParameters;
  if(!searched.calibrated || !current.calibrated)return reject(Reason.UNCALIBRATED_PARAMETERS);
  if(searched.revision!=current.revision || Double.compare(searched.matchThreshold,current.matchThreshold)!=0
    || Double.compare(searched.minimumMargin,current.minimumMargin)!=0
    || searched.maximumFrameAgeMillis!=current.maximumFrameAgeMillis)return reject(Reason.STALE_PARAMETERS);
  if(input.libraryStatus!=FaceLibrary.Status.READY)return reject(Reason.LIBRARY_NOT_READY);
  FaceLibrary.Snapshot library=input.currentLibrary;
  if(!input.expectedScope.equals(library.scope))return reject(Reason.SCOPE_MISMATCH);
  if(!library.revision.equals(input.searchedLibraryRevision))return reject(Reason.STALE_LIBRARY);
  if(library.entries.isEmpty())return reject(Reason.EMPTY_LIBRARY);
  if(input.quality!=FaceEngine.StepResult.PASSED)return reject(Reason.QUALITY_REJECTED);
  if(input.liveness!=FaceEngine.StepResult.PASSED)return reject(Reason.LIVENESS_REJECTED);
  if(input.candidates.isEmpty())return reject(Reason.NO_CANDIDATES);
  Map<Integer,FaceLibrary.Entry> people=new HashMap<>();
  for(FaceLibrary.Entry entry:library.entries)people.put(entry.id,entry);
  Set<Integer> seen=new HashSet<>();
  for(FaceEngine.Candidate candidate:input.candidates){
   if(candidate==null || candidate.id<1 || !Double.isFinite(candidate.score) || candidate.score<0)
    return reject(Reason.INVALID_CANDIDATE);
   if(!seen.add(candidate.id))return reject(Reason.DUPLICATE_CANDIDATE);
   if(!people.containsKey(candidate.id))return reject(Reason.UNKNOWN_PERSON);
  }
  if(library.entries.size()>1 && input.candidates.size()<2)return reject(Reason.INCOMPLETE_TOP_TWO);
  List<FaceEngine.Candidate> ranked=new ArrayList<>(input.candidates);
  ranked.sort((a,b)->{int score=Double.compare(b.score,a.score);return score!=0?score:Integer.compare(a.id,b.id);});
  FaceEngine.Candidate best=ranked.get(0);
  if(best.score<current.matchThreshold)return reject(Reason.LOW_SCORE);
  if(ranked.size()>1){
   double second=ranked.get(1).score;
   if(best.score==second || best.score-second<current.minimumMargin)return reject(Reason.AMBIGUOUS);
  }
  return new Result(Reason.PASSED,new Person(people.get(best.id),library,current.revision));
 }
}
