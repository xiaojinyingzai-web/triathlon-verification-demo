package com.triathlon.prep;
/** One-use lease. Cancellation never acknowledges a native open which is still pending. */
final class PreviewOnlyCloseState {
 private enum State { NEW, OPENING, OPEN, PREVIEW, CLOSING, CLOSED }
 private State state=State.NEW;
 synchronized boolean begin(){if(state!=State.NEW)return false;state=State.OPENING;return true;}
 synchronized boolean opened(){if(state!=State.OPENING)return false;state=State.OPEN;return true;}
 synchronized boolean configured(){if(state!=State.OPEN)return false;state=State.PREVIEW;return true;}
 synchronized void stop(){if(state==State.NEW)state=State.CLOSED;else if(state!=State.CLOSED)state=State.CLOSING;}
 synchronized void openFailed(){if(state==State.OPENING||state==State.CLOSING)state=State.CLOSED;}
 synchronized void deviceClosed(){state=State.CLOSED;}
 synchronized boolean released(){return state==State.CLOSED;}
}
