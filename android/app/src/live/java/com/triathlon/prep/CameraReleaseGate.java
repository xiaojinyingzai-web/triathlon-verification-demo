package com.triathlon.prep;
/** Serializes native opening until the previous owner confirms resource/device release. */
final class CameraReleaseGate {
 private Object current;
 synchronized boolean acquire(Object owner){if(owner==null||current!=null)return false;current=owner;return true;}
 synchronized void released(Object owner){if(owner!=null&&current==owner)current=null;}
}
