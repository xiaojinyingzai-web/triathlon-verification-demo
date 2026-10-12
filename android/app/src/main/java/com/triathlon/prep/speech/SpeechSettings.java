package com.triathlon.prep.speech;
import java.util.*;
public final class SpeechSettings {
 public enum Scene { WAIT_CARD,FACE_CAMERA,PASSED,REJECTED,NOT_LISTED }
 public final boolean enabled;public final int volumePercent;private final Map<Scene,String> values;
 public SpeechSettings(boolean enabled,int volumePercent,Map<Scene,String> texts){
  if(volumePercent<0||volumePercent>100||texts==null||texts.size()!=Scene.values().length)throw new IllegalArgumentException("Complete settings and volume 0-100 required");
  EnumMap<Scene,String> copy=new EnumMap<>(Scene.class);for(Scene s:Scene.values()){if(!texts.containsKey(s))throw new IllegalArgumentException("All scenes required");copy.put(s,normalize(texts.get(s)));}
  this.enabled=enabled;this.volumePercent=volumePercent;this.values=Collections.unmodifiableMap(copy);
 }
 public static SpeechSettings defaults(){Map<Scene,String> m=new EnumMap<>(Scene.class);for(Scene s:Scene.values())m.put(s,"");return new SpeechSettings(false,70,m);}
 public Map<Scene,String> texts(){return values;}
 public String text(Scene scene){String value=values.get(scene);if(value==null)throw new IllegalArgumentException("Scene required");return value.isEmpty()?defaultText(scene):value;}
 public static String defaultText(Scene scene){switch(scene){case WAIT_CARD:return "请放置身份证";case FACE_CAMERA:return "请正对摄像头";case PASSED:return "核验通过";case REJECTED:return "核验未通过，请联系工作人员";case NOT_LISTED:return "未查询到参赛信息，请联系工作人员";default:throw new IllegalArgumentException("Scene required");}}
 public static String normalize(String raw){
  if(raw==null)throw new IllegalArgumentException("Text required");
  for(int i=0;i<raw.length();){char ch=raw.charAt(i);if(Character.isSurrogate(ch)&&(!Character.isHighSurrogate(ch)||i+1==raw.length()||!Character.isLowSurrogate(raw.charAt(i+1))))throw new IllegalArgumentException("Invalid Unicode");int cp=raw.codePointAt(i);if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT)throw new IllegalArgumentException("Control characters prohibited");i+=Character.charCount(cp);}
  int start=0,end=raw.length();while(start<end){int cp=raw.codePointAt(start);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))break;start+=Character.charCount(cp);}while(end>start){int cp=raw.codePointBefore(end);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))break;end-=Character.charCount(cp);}
  String value=raw.substring(start,end);if(value.codePointCount(0,value.length())>100)throw new IllegalArgumentException("At most 100 code points");return value;
 }
}
