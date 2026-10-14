package com.triathlon.prep;
import com.triathlon.prep.core.ReceiptTemplate;
/** Worker-owned confirmed snapshot. Drafts are UI-local until a successful atomic commit. */
public final class ReceiptTemplateSetting {
 public interface Store {ReceiptTemplate load()throws Exception;boolean save(ReceiptTemplate template)throws Exception;}
 private final Store store;private ReceiptTemplate confirmed;private boolean readFailed;
 public ReceiptTemplateSetting(Store store,String legacyHeading){if(store==null)throw new IllegalArgumentException("Template store required");this.store=store;confirmed=ReceiptTemplate.defaults();try{ReceiptTemplate loaded=store.load();confirmed=loaded!=null?loaded:confirmed.withText(ReceiptTemplate.TextKey.EVENT_HEADING,legacyHeading==null?"":legacyHeading);}catch(Exception|LinkageError ex){readFailed=true;try{confirmed=confirmed.withText(ReceiptTemplate.TextKey.EVENT_HEADING,legacyHeading==null?"":legacyHeading);}catch(RuntimeException ignored){/* An unconfirmed fallback never overwrites storage. */}}}
 public synchronized ReceiptTemplate confirmed(){return confirmed;}
 public synchronized boolean readFailed(){return readFailed;}
 public synchronized boolean save(ReceiptTemplate draft){if(draft==null)return false;try{if(!store.save(draft))return false;confirmed=draft;readFailed=false;return true;}catch(Exception|LinkageError ex){return false;}}
}
