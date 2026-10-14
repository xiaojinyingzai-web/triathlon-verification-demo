package com.triathlon.prep.core;
import java.util.List;
/** Success registration is a committed fact, independent of print attempts or audit sessions. */
public final class CheckIn {
 private CheckIn(){}
 public enum State { FIRST,DUPLICATE,NOT_SUCCESS,UNLINKABLE }
 public static boolean trustedSuccess(Verification.Record r){return r!=null&&r.mode!=null&&r.verdict==Verification.Verdict.PASSED&&r.membership==Verification.Membership.MATCHED&&validKey(r.rosterBatchId,r.rosterBib);}
 public static boolean validKey(String batch,String bib){return batch!=null&&batch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")&&bib!=null&&!bib.trim().isEmpty();}
 public static boolean same(Verification.Record r,Verification.Mode mode,String batch,String bib){return r!=null&&r.mode==mode&&batch.equals(r.rosterBatchId)&&bib.equals(r.rosterBib);}
 /** Existing records are ordered by durable insertion, not wall time. Unkeyed legacy cannot veto unrelated people. */
 public static State classify(Verification.Record proposed,List<Verification.Record> existing,boolean scopedUnknown){
  if(!trustedSuccess(proposed))return State.NOT_SUCCESS;
  if(existing==null)throw new IllegalArgumentException("Committed history required");
  boolean unknown=scopedUnknown;
  for(Verification.Record r:existing){if(!same(r,proposed.mode,proposed.rosterBatchId,proposed.rosterBib))continue;if(trustedSuccess(r))return r.sessionId.equals(proposed.sessionId)?State.FIRST:State.DUPLICATE;if(r.verdict==Verification.Verdict.PASSED&&r.membership==Verification.Membership.LEGACY_NOT_EVALUATED)unknown=true;}
  return unknown?State.UNLINKABLE:State.FIRST;
 }
}
