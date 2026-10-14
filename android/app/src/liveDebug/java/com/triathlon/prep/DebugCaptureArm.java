package com.triathlon.prep;
/** Operator intent plus current paired preview evidence; one shot, revoked on cancellation. */
final class DebugCaptureArm {
 private boolean ready,armed,consumed,closed;
 synchronized void ready(boolean fresh){if(!closed)ready=fresh;}
 synchronized boolean arm(){if(closed||armed||consumed||!ready)return false;armed=true;return true;}
 synchronized boolean consume(boolean fresh){if(closed||!armed||consumed||!fresh)return false;consumed=true;return true;}
 synchronized boolean requested(){return armed||consumed;}
 synchronized void close(){closed=true;ready=false;armed=false;}
}
