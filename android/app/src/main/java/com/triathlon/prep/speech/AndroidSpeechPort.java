package com.triathlon.prep.speech;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.*;
import android.speech.tts.*;
import java.util.*;
/** Existing system TTS only. No data installation intents, network voice or cloud SDK. */
public final class AndroidSpeechPort implements SpeechPort {
 private final Handler main=new Handler(Looper.getMainLooper());private final Runnable changed;
 private volatile Readiness readiness=Readiness.INITIALIZING;private TextToSpeech tts;
 private boolean closed;private long epoch;private Request current;private Callback callback;
 public AndroidSpeechPort(Context context,Runnable changed){this.changed=changed;Context app=context.getApplicationContext();main.post(()->{
  synchronized(this){if(closed)return;try{tts=new TextToSpeech(app,status->main.post(()->initialized(status)));}catch(RuntimeException|LinkageError ex){readiness=Readiness.FAILED;notifyChanged();}}
 });}
 private synchronized void initialized(int status){
  if(closed)return;
  try{
   if(tts==null||status!=TextToSpeech.SUCCESS){readiness=tts!=null&&tts.getEngines().isEmpty()?Readiness.NO_ENGINE:Readiness.FAILED;notifyChanged();return;}
   List<Voice> local=new ArrayList<>();Set<Voice> voices=tts.getVoices();
   if(voices!=null)for(Voice v:voices)if(v!=null&&v.getLocale()!=null&&"zh".equals(v.getLocale().getLanguage())&&!v.isNetworkConnectionRequired()&&(v.getFeatures()==null||!v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))&&tts.isLanguageAvailable(v.getLocale())>=TextToSpeech.LANG_AVAILABLE)local.add(v);
   Collections.sort(local,(a,b)->{int x="CN".equals(a.getLocale().getCountry())?0:1,y="CN".equals(b.getLocale().getCountry())?0:1;return x!=y?x-y:a.getName().compareTo(b.getName());});
   Voice selected=null;for(Voice v:local)if(tts.setVoice(v)==TextToSpeech.SUCCESS){selected=v;break;}
   if(selected==null){readiness=Readiness.NO_CHINESE;notifyChanged();return;}
   if(tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())!=TextToSpeech.SUCCESS){readiness=Readiness.FAILED;notifyChanged();return;}
   if(tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){report(id,Progress.STARTED);}public void onDone(String id){report(id,Progress.DONE);}public void onError(String id){report(id,Progress.FAILED);}public void onError(String id,int code){report(id,Progress.FAILED);}public void onStop(String id,boolean interrupted){report(id,Progress.CANCELLED);}})!=TextToSpeech.SUCCESS){readiness=Readiness.FAILED;notifyChanged();return;}
   readiness=Readiness.READY;notifyChanged();
  }catch(RuntimeException|LinkageError ex){readiness=Readiness.FAILED;notifyChanged();}
 }
 public Readiness readiness(){return readiness;}
 public synchronized boolean speak(Request request,Callback next){
  if(closed||readiness!=Readiness.READY||request==null||next==null)return false;
  final long token=++epoch;current=request;callback=next;
  main.post(()->{TextToSpeech engine;synchronized(this){if(closed||epoch!=token||readiness!=Readiness.READY)return;engine=tts;}
   try{Voice v=engine.getVoice();if(v==null||v.getLocale()==null||!"zh".equals(v.getLocale().getLanguage())||v.isNetworkConnectionRequired()||(v.getFeatures()!=null&&v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))){readiness=Readiness.NO_CHINESE;report(request.id,Progress.FAILED);notifyChanged();return;}Bundle parameters=new Bundle();parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,request.volume);int result=engine.speak(request.text,TextToSpeech.QUEUE_FLUSH,parameters,request.id);if(result!=TextToSpeech.SUCCESS)report(request.id,Progress.FAILED);}catch(RuntimeException|LinkageError ex){report(request.id,Progress.FAILED);}
  });
  return true;
 }
 private void report(String id,Progress progress){Callback target;synchronized(this){if(closed||current==null||!current.id.equals(id))return;target=callback;}if(target!=null)target.changed(progress);}
 public synchronized void stop(){epoch++;current=null;callback=null;main.post(()->{TextToSpeech engine;synchronized(this){engine=tts;}if(engine!=null)try{engine.stop();}catch(RuntimeException ignored){}});}
 public synchronized void close(){if(closed)return;closed=true;readiness=Readiness.CLOSED;epoch++;current=null;callback=null;main.post(()->{TextToSpeech engine;synchronized(this){engine=tts;tts=null;}if(engine!=null){try{engine.stop();}catch(RuntimeException ignored){}try{engine.shutdown();}catch(RuntimeException ignored){}}});}
 private void notifyChanged(){try{changed.run();}catch(RuntimeException ignored){}}
}
