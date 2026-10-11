package com.triathlon.prep;
import com.triathlon.prep.core.CheckInReceipt;
/** Durable confirmed heading; all I/O is called by Host's serial worker. */
public final class ReceiptNameSetting {
 public interface Store {String load()throws Exception;boolean save(String value)throws Exception;}
 private final Store store;private String confirmed="";private boolean readFailed;
 public ReceiptNameSetting(Store store){
  if(store==null)throw new IllegalArgumentException("Name store required");this.store=store;
  try{confirmed=CheckInReceipt.normalizeHeading(store.load());}catch(Exception|LinkageError ex){readFailed=true;}
 }
 public synchronized String override(){return confirmed;}
 public synchronized boolean readFailed(){return readFailed;}
 public synchronized boolean save(String draft){
  try{String next=CheckInReceipt.normalizeHeading(draft);if(!store.save(next))return false;confirmed=next;readFailed=false;return true;}
  catch(Exception|LinkageError ex){return false;}
 }
}
