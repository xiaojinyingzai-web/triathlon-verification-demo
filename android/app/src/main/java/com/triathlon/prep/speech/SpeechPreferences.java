package com.triathlon.prep.speech;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
/** One private snapshot, independent of roster, card fences and print reservations. Worker only. */
public final class SpeechPreferences implements SpeechSetting.Store {
 public static final String NAME="triathlon_speech_v1";private final SharedPreferences preferences;
 public SpeechPreferences(Context context){preferences=context.getApplicationContext().getSharedPreferences(NAME,Context.MODE_PRIVATE);}
 @Override public synchronized SpeechSettings load(){String raw=preferences.getString("snapshot",null);if(raw==null)return null;return decode(raw);}
 @Override public synchronized boolean save(SpeechSettings next){
  String previous;try{previous=preferences.getString("snapshot",null);}catch(RuntimeException ex){return false;}
  try{if(preferences.edit().putString("snapshot",encode(next)).commit())return true;}catch(RuntimeException ignored){}
  try{SharedPreferences.Editor rollback=preferences.edit();if(previous==null)rollback.remove("snapshot");else rollback.putString("snapshot",previous);rollback.commit();}catch(RuntimeException ignored){}
  return false;
 }
 private static String encode(SpeechSettings s){try{JSONObject json=new JSONObject();json.put("version",1);json.put("enabled",s.enabled);json.put("volume",s.volumePercent);JSONObject texts=new JSONObject();for(SpeechSettings.Scene scene:SpeechSettings.Scene.values())texts.put(scene.name(),s.texts().get(scene));json.put("texts",texts);return json.toString();}catch(JSONException ex){throw new IllegalArgumentException("Settings encoding failed",ex);}}
 private static SpeechSettings decode(String raw){try{JSONObject json=new JSONObject(raw);if(json.getInt("version")!=1||!(json.get("enabled") instanceof Boolean)||!(json.get("volume") instanceof Integer))throw new IllegalArgumentException("Invalid settings schema");JSONObject texts=json.getJSONObject("texts");if(texts.length()!=5)throw new IllegalArgumentException("All scenes required");Map<SpeechSettings.Scene,String> map=new EnumMap<>(SpeechSettings.Scene.class);for(SpeechSettings.Scene scene:SpeechSettings.Scene.values()){Object value=texts.get(scene.name());if(!(value instanceof String))throw new IllegalArgumentException("Invalid scene text");map.put(scene,(String)value);}return new SpeechSettings(json.getBoolean("enabled"),json.getInt("volume"),map);}catch(JSONException ex){throw new IllegalArgumentException("Settings decoding failed",ex);}}
}
