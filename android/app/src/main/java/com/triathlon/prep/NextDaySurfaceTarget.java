package com.triathlon.prep;
import android.os.Parcel;import android.view.Surface;
/** Main-thread supplied Surface duplicate; camera borrows its own reference until close ack. */
final class NextDaySurfaceTarget {
 private PreviewLease<Surface> target;private String token="";private long revision;private int outstanding;
 private java.util.function.LongConsumer frames;
 synchronized long revision(){return revision;}
 synchronized boolean ready(){return target!=null&&target.value()!=null;}
 synchronized boolean released(){return outstanding==0;}
 synchronized boolean matches(String offered,long rev){return token.equals(offered)&&revision==rev;}
 void available(String offered,long rev,Surface source){
  if(offered==null||rev<1||source==null||!source.isValid())return;
  Parcel parcel=Parcel.obtain();Surface duplicate;
  try{source.writeToParcel(parcel,0);parcel.setDataPosition(0);duplicate=Surface.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
  clear();synchronized(this){token=offered;revision=rev;outstanding++;target=new PreviewLease<>(duplicate,()->{duplicate.release();synchronized(NextDaySurfaceTarget.this){outstanding--;}});}
 }
 void destroyed(String offered,long rev){synchronized(this){if(!token.equals(offered)||revision!=rev)return;}clear();}
 void clear(){PreviewLease<Surface> old;synchronized(this){old=target;target=null;token="";revision=0;frames=null;}if(old!=null)old.invalidate();}
 synchronized PreviewLease<Surface> lease(long offered){return revision==offered?target:null;}
 synchronized void watch(long offered,java.util.function.LongConsumer callback){if(revision==offered)frames=callback;}
 void frame(String offered,long rev,long timestamp){java.util.function.LongConsumer callback;synchronized(this){if(!token.equals(offered)||revision!=rev||target==null||target.value()==null||timestamp<=0)return;callback=frames;}if(callback!=null)callback.accept(timestamp);}
}
