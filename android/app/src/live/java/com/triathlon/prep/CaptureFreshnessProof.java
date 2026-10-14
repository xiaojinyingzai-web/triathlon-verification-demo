package com.triathlon.prep;
/** Sensor-domain proof only. Never compares UNKNOWN sensor timestamps to a process clock. */
final class CaptureFreshnessProof {
 static final class Lease {
  final Object session,request;final long ticket,barrierFrame,barrierTimestamp;final boolean controlled;
  long startFrame=-1,startTimestamp,resultFrame=-1,resultTimestamp;boolean delivered,broken;
  Lease(Object s,Object r,long t,boolean c,long f,long ns){session=s;request=r;ticket=t;controlled=c;barrierFrame=f;barrierTimestamp=ns;}
 }
 private Lease active;
 synchronized Lease begin(Object session,Object request,long ticket,boolean controlled,long frame,long timestamp){
  if(session==null||request==null||ticket<1||!controlled&&(frame<0||timestamp<=0))throw new IllegalArgumentException("Current preview sensor watermark required without ZSL control");
  return active=new Lease(session,request,ticket,controlled,frame,timestamp);
 }
 synchronized boolean current(Lease l){return l!=null&&l==active&&!l.delivered&&!l.broken;}
 synchronized boolean matches(Lease l,Object session,Object request){return current(l)&&l.session==session&&l.request==request;}
 synchronized void started(Lease l,Object session,Object request,long frame,long timestamp){
  if(!matches(l,session,request))return;
  if(frame<0||timestamp<=0||l.startTimestamp!=0&&(l.startFrame!=frame||l.startTimestamp!=timestamp)){l.broken=true;return;}
  l.startFrame=frame;l.startTimestamp=timestamp;
 }
 synchronized void completed(Lease l,Object session,Object request,long frame,long timestamp){
  if(!matches(l,session,request))return;
  if(frame<0||timestamp<=0||l.resultTimestamp!=0&&(l.resultFrame!=frame||l.resultTimestamp!=timestamp)){l.broken=true;return;}
  l.resultFrame=frame;l.resultTimestamp=timestamp;
 }
 synchronized long expected(Lease l){
  if(!current(l))return 0;
  if(l.controlled)return l.startTimestamp>0?l.startTimestamp:l.resultTimestamp;
  if(l.startTimestamp<=l.barrierTimestamp||l.startFrame<=l.barrierFrame||l.startTimestamp!=l.resultTimestamp||l.startFrame!=l.resultFrame)return 0;
  return l.resultTimestamp;
 }
 synchronized boolean canStage(Lease l,long timestamp){return current(l)&&timestamp>0&&(l.controlled||timestamp>l.barrierTimestamp);}
 synchronized boolean acceptImage(Lease l,long timestamp){long expected=expected(l);if(expected<=0||timestamp!=expected)return false;l.delivered=true;return true;}
 synchronized void cancel(){active=null;}
}
