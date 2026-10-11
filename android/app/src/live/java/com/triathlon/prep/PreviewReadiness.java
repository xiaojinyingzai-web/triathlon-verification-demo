package com.triathlon.prep;
import java.util.LinkedHashMap;
/** Pairs actual texture and camera timestamps; no pixels, face scores or camera ownership. */
final class PreviewReadiness {
 private final long stableMillis,freshMillis;
 private long camera,surface,floor,matched,first=-1,last;
 private final LinkedHashMap<Long,Long> cameras=new LinkedHashMap<>(),surfaces=new LinkedHashMap<>();
 private boolean configured,invalid;
 PreviewReadiness(long stableMillis,long freshMillis){
  if(stableMillis<0||stableMillis>30000||freshMillis<1||freshMillis>5000)throw new IllegalArgumentException("Preview timing required");
  this.stableMillis=stableMillis;this.freshMillis=freshMillis;
 }
 synchronized void configured(){if(invalid)return;floor=Math.max(camera,surface);configured=true;}
 synchronized void camera(long timestamp,long now){if(invalid||timestamp<=camera)return;camera=timestamp;remember(cameras,timestamp,now);match(timestamp,now);}
 synchronized void surface(long timestamp,long now){if(invalid||timestamp<=surface)return;surface=timestamp;remember(surfaces,timestamp,now);match(timestamp,now);}
 private static void remember(LinkedHashMap<Long,Long> values,long ts,long now){values.put(ts,now);if(values.size()>8)values.remove(values.keySet().iterator().next());}
 private void match(long ts,long now){
  if(configured&&ts>floor&&ts>matched&&cameras.containsKey(ts)&&surfaces.containsKey(ts)){
   matched=ts;if(first<0)first=now;last=Math.min(cameras.get(ts),surfaces.get(ts));
  }
 }
 synchronized void invalidate(){invalid=true;cameras.clear();surfaces.clear();}
 synchronized boolean hasFrame(){return !invalid&&configured&&first>=0;}
 synchronized boolean ready(long now){return !invalid&&configured&&first>=0&&now>=first&&now-first>=stableMillis&&now>=last&&now-last<=freshMillis;}
}
