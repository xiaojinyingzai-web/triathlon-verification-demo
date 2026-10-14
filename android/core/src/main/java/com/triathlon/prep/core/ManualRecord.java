package com.triathlon.prep.core;
/** Separate manual business result. Deliberately has no automatic verdict, liveness or score. */
public final class ManualRecord {
 public enum Method { MANUAL }public enum Decision { CONFIRMED }
 public final String id,rosterBatchId,rosterBib;public final long timestamp;public final Verification.Mode mode;
 public final Method method=Method.MANUAL;public final Decision decision=Decision.CONFIRMED;
 public ManualRecord(String id,long timestamp,Verification.Mode mode,String batch,String bib){if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||batch==null||!batch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||mode==null||timestamp<0)throw new IllegalArgumentException("Committed manual identity required");this.id=id;this.timestamp=timestamp;this.mode=mode;rosterBatchId=batch;rosterBib=RaceRoster.required(bib,80);}
 public String key(){return mode.name()+"|"+rosterBatchId+"|"+rosterBib;}
}
