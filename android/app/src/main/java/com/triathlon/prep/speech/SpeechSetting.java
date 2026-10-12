package com.triathlon.prep.speech;
public final class SpeechSetting {
 public interface Store {SpeechSettings load();boolean save(SpeechSettings next);}
 private final Store store;private SpeechSettings current=SpeechSettings.defaults();private boolean readFailed;
 public SpeechSetting(Store store){if(store==null)throw new IllegalArgumentException("Store required");this.store=store;try{SpeechSettings loaded=store.load();if(loaded!=null)current=loaded;}catch(RuntimeException ex){readFailed=true;}}
 public synchronized SpeechSettings current(){return current;}public synchronized boolean readFailed(){return readFailed;}
 public synchronized boolean save(SpeechSettings next){if(next==null)throw new IllegalArgumentException("Settings required");try{if(!store.save(next))return false;current=next;readFailed=false;return true;}catch(RuntimeException ex){return false;}}
}
