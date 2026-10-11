package com.triathlon.prep;
import com.triathlon.prep.core.Verification;
import java.util.Arrays;
/** Passive chip display before core advances to capture; verdict and input bytes remain unchanged. */
final class DisplayedDeviceAdapter implements Verification.DeviceAdapter {
 private final Verification.DeviceAdapter device;private final VerificationDisplay display;
 private final Object lock=new Object();private long generation;private String current;
 DisplayedDeviceAdapter(Verification.DeviceAdapter device,VerificationDisplay display){this.device=device;this.display=display;}
 public Verification.Mode mode(){return device.mode();}
 public void readCard(String id,Verification.Reply<Verification.Card> reply){final long token;synchronized(lock){current=id;token=++generation;}device.readCard(id,new Verification.Reply<Verification.Card>(){
  public void ok(Verification.Card card){byte[] copy=card.photoCopy();try{synchronized(lock){if(token==generation&&id.equals(current))display.chip(id,copy);}}finally{if(copy!=null)Arrays.fill(copy,(byte)0);}reply.ok(card);}
  public void fail(Verification.Error error){reply.fail(error);}
 });}
 public void capture(String id,Verification.Reply<Verification.Capture> reply){device.capture(id,reply);}
 public void cancel(String id){synchronized(lock){if(id!=null&&id.equals(current)){current=null;generation++;}}device.cancel(id);}
}
