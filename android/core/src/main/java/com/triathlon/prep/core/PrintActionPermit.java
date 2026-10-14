package com.triathlon.prep.core;
import java.util.UUID;
/** Host-owned, one-use authority for the exact current printing action; not a UI permission. */
public final class PrintActionPermit {
 private String key="",token="";private long revision;private boolean consumed;
 public synchronized void update(String current,boolean enabled){if(current==null)throw new IllegalArgumentException();if(!enabled){if(!token.isEmpty()||!key.isEmpty())invalidate();return;}if(!current.equals(key)){key=current;token=UUID.randomUUID().toString();revision++;consumed=false;}}
 public synchronized String token(){return token;}
 public synchronized long revision(){return revision;}
 public synchronized boolean consume(String offered,long offeredRevision,boolean requiresConfirmation,boolean confirmed){if(token.isEmpty()||consumed||!token.equals(offered)||revision!=offeredRevision||requiresConfirmation&&!confirmed)return false;consumed=true;token="";return true;}
 public synchronized void invalidate(){key="";token="";revision++;consumed=true;}
}
