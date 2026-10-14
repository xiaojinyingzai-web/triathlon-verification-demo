package com.triathlon.prep.core;
import java.io.IOException;import java.util.List;
public interface ManualRecordStore {List<ManualRecord> list()throws IOException;/** Returns the existing result for the same mode/batch/bib instead of creating duplicate completion. */ManualRecord commit(ManualRecord proposed)throws IOException;
 final class Commit {public final ManualRecord record;public final CheckIn.State checkIn;public Commit(ManualRecord r,CheckIn.State state){record=r;checkIn=state;}}
 default Commit commitCheckIn(ManualRecord proposed)throws IOException{synchronized(this){ManualRecord previous=find(proposed.mode,proposed.rosterBatchId,proposed.rosterBib);ManualRecord saved=commit(proposed);return new Commit(saved,previous==null?CheckIn.State.FIRST:CheckIn.State.DUPLICATE);}}
 default ManualRecord find(Verification.Mode mode,String batch,String bib)throws IOException{for(ManualRecord r:list())if(r.mode==mode&&r.rosterBatchId.equals(batch)&&r.rosterBib.equals(bib))return r;return null;}}
