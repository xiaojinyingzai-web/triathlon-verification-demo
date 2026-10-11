package com.triathlon.prep;
/** Owns only projection lifecycle. The one business Host is supplied separately. */
final class DisplayWindowSession implements AutoCloseable {
 enum Role { STAFF,ATHLETE,SETUP }
 interface Port {AutoCloseable show(Role role,int displayId);void activity(Role role);}
 private final Port port;private AutoCloseable staffWindow,athleteWindow;
 private Integer staff,athlete;private int activityId=-1;private boolean activityWasAthlete;
 DisplayWindowSession(Port port){if(port==null)throw new IllegalArgumentException();this.port=port;}
 boolean sync(DisplayRoutes roles,int current){
  Integer s=roles.staff(),a=roles.athlete();
  if(s==null){boolean had=staff!=null||athlete!=null;dispose();if(had)port.activity(activityWasAthlete?Role.SETUP:Role.STAFF);return true;}
  if(s.equals(staff)&&a.equals(athlete)&&activityId==current)return true;
  dispose();activityId=current;activityWasAthlete=activityWasAthlete||a==current;
  try{
   if(s!=current)staffWindow=port.show(Role.STAFF,s);
   if(a!=current)athleteWindow=port.show(Role.ATHLETE,a);
   port.activity(s==current?Role.STAFF:a==current?Role.ATHLETE:Role.SETUP);
   staff=s;athlete=a;if(s==current)activityWasAthlete=false;return true;
  }catch(RuntimeException|LinkageError ex){dispose();roles.clear();port.activity(Role.SETUP);return false;}
 }
 private void dispose(){AutoCloseable s=staffWindow,a=athleteWindow;staffWindow=null;athleteWindow=null;staff=null;athlete=null;close(s);close(a);}
 private static void close(AutoCloseable window){if(window!=null)try{window.close();}catch(Exception|LinkageError ignored){}}
 public void close(){dispose();}
}
