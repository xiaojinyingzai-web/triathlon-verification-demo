package com.triathlon.prep.speech;
public interface SpeechPort {
 enum Readiness { INITIALIZING,READY,NO_ENGINE,NO_CHINESE,FAILED,CLOSED }
 enum Progress { IDLE,QUEUED,STARTED,DONE,FAILED,CANCELLED,MUTED }
 final class Request {public final String id,text;public final float volume;public Request(String id,String text,float volume){this.id=id;this.text=text;this.volume=volume;}}
 interface Callback {void changed(Progress progress);}
 Readiness readiness();boolean speak(Request request,Callback callback);void stop();void close();
}
