package com.triathlon.prep;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import java.util.*;
/** Enumeration/listener seam only: no windows, athlete page, display launch or hardware-name guessing. */
public final class AndroidDisplayRoutes implements AutoCloseable {
 public final DisplayRoutes roles=new DisplayRoutes();
 private final DisplayManager manager;private boolean registered;
 private final DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener(){
  public void onDisplayAdded(int id){refresh();}public void onDisplayRemoved(int id){refresh();}public void onDisplayChanged(int id){refresh();}
 };
 public AndroidDisplayRoutes(Context context){manager=(DisplayManager)context.getApplicationContext().getSystemService(Context.DISPLAY_SERVICE);refresh();}
 public synchronized void start(){if(manager!=null&&!registered){manager.registerDisplayListener(listener,new Handler(Looper.getMainLooper()));registered=true;}refresh();}
 public void refresh(){List<DisplayRoutes.Screen> entries=new ArrayList<>();if(manager!=null)for(Display d:manager.getDisplays()){
  DisplayMetrics m=new DisplayMetrics();d.getRealMetrics(m);entries.add(new DisplayRoutes.Screen(d.getDisplayId(),d.getName(),m.widthPixels,m.heightPixels,m.densityDpi,d.getRotation(),d.isValid()));
 }roles.update(entries);}
 public boolean select(int staff,int athlete){refresh();return roles.select(staff,athlete);}
 public synchronized void close(){if(manager!=null&&registered)manager.unregisterDisplayListener(listener);registered=false;roles.update(Collections.emptyList());}
}
