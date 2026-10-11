package com.triathlon.prep;
import java.util.*;
/** Explicit logical display roles, independent of manufacturer names, dimensions and OS default. */
public final class DisplayRoutes {
 public enum Status { UNSELECTED,SINGLE_DISPLAY,DISCONNECTED,READY }
 public static final class Screen {
  public final int id,width,height,densityDpi,rotation;public final String name;public final boolean valid;
  public Screen(int id,String name,int width,int height,int densityDpi,int rotation,boolean valid){this.id=id;this.name=name;this.width=width;this.height=height;this.densityDpi=densityDpi;this.rotation=rotation;this.valid=valid;}
 }
 private Map<Integer,Screen> screens=new LinkedHashMap<>();private Integer staff,athlete;private boolean disconnected;
 public synchronized void update(List<Screen> entries){
  Map<Integer,Screen> next=new LinkedHashMap<>();for(Screen s:entries)if(s!=null&&s.valid&&s.width>0&&s.height>0)next.put(s.id,s);
  if(staff!=null&&(!next.containsKey(staff)||!next.containsKey(athlete))){staff=null;athlete=null;disconnected=true;}
  else if(disconnected&&next.size()>1)disconnected=false;
  screens=next;
 }
 public synchronized boolean select(int staff,int athlete){
  if(staff==athlete||!screens.containsKey(staff)||!screens.containsKey(athlete))return false;
  this.staff=staff;this.athlete=athlete;disconnected=false;return true;
 }
 public synchronized void clear(){staff=null;athlete=null;disconnected=false;}
 public synchronized Status status(){if(disconnected)return Status.DISCONNECTED;if(screens.size()<2)return Status.SINGLE_DISPLAY;return staff==null?Status.UNSELECTED:Status.READY;}
 public synchronized List<Screen> screens(){return Collections.unmodifiableList(new ArrayList<>(screens.values()));}
 public synchronized Integer staff(){return status()==Status.READY?staff:null;}
 public synchronized Integer athlete(){return status()==Status.READY?athlete:null;}
}
