package com.triathlon.prep.nextday.records;

import com.triathlon.prep.core.Verification;
import java.util.UUID;
import java.util.Objects;

/** Minimal audit identity; Host must validate candidate mapping and current action ticket. */
public final class NextDayRecord {
 public enum Outcome { PASSED, REJECTED, MANUAL_REQUIRED }
 public final String operationId, batch, libraryRevision;
 public final Verification.Mode mode;
 public final long parameterRevision, completedAtMillis;
 public final Integer candidateId;
 public final Outcome outcome;
 public NextDayRecord(String operationId, Verification.Mode mode, String batch,
   String libraryRevision, long parameterRevision, Integer candidateId,
   Outcome outcome, long completedAtMillis) {
  if(mode==null||outcome==null||parameterRevision<0||completedAtMillis<0
    ||(candidateId!=null&&candidateId<=0)||(outcome==Outcome.PASSED&&candidateId==null))
   throw new IllegalArgumentException("Invalid next-day record");
  this.operationId=uuid(operationId);this.batch=uuid(batch);this.libraryRevision=uuid(libraryRevision);
  this.mode=mode;this.parameterRevision=parameterRevision;this.candidateId=candidateId;
  this.outcome=outcome;this.completedAtMillis=completedAtMillis;
 }
 private static String uuid(String value) {
  if(value==null||value.length()!=36)throw new IllegalArgumentException("UUID required");
  String canonical=UUID.fromString(value).toString();
  if(!canonical.equals(value))throw new IllegalArgumentException("Canonical UUID required");
  return canonical;
 }
 @Override public boolean equals(Object value) {
  if(!(value instanceof NextDayRecord))return false;
  NextDayRecord r=(NextDayRecord)value;
  return operationId.equals(r.operationId)&&mode==r.mode&&batch.equals(r.batch)
   &&libraryRevision.equals(r.libraryRevision)&&parameterRevision==r.parameterRevision
   &&Objects.equals(candidateId,r.candidateId)&&outcome==r.outcome&&completedAtMillis==r.completedAtMillis;
 }
 @Override public int hashCode(){return Objects.hash(operationId,mode,batch,libraryRevision,parameterRevision,candidateId,outcome,completedAtMillis);}
}
