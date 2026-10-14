package com.triathlon.prep.nextday.baidu;
import java.io.IOException;import java.nio.*;import java.nio.charset.*;import java.util.*;
/** Strict bounded profile/manifest parser; no logging of input, duplicate keys or extension fields. */
final class PreparationJson {
 private final String text;private int at,nodes;
 private PreparationJson(String text){this.text=text;}
 static Map<String,Object> read(byte[] bytes)throws IOException{
  if(bytes==null||bytes.length==0||bytes.length>65536)throw bad();
  try{String s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();PreparationJson p=new PreparationJson(s);Object value=p.value(0);p.space();if(p.at!=s.length()||!(value instanceof Map))throw bad();return (Map<String,Object>)value;}catch(CharacterCodingException e){throw bad();}
 }
 private static IOException bad(){return new IOException("PREPARATION_JSON_INVALID");}
 private void space(){while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0)at++;}
 private boolean take(char c){space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
 private void need(char c)throws IOException{if(!take(c))throw bad();}
 private Object value(int depth)throws IOException{
  space();if(depth>16||++nodes>4096||at>=text.length())throw bad();char c=text.charAt(at);
  if(c=='{'){at++;Map<String,Object> out=new LinkedHashMap<>();if(take('}'))return out;do{space();String key=string();need(':');if(out.containsKey(key))throw bad();out.put(key,value(depth+1));}while(take(','));need('}');return out;}
  if(c=='['){at++;List<Object> out=new ArrayList<>();if(take(']'))return out;do{out.add(value(depth+1));}while(take(','));need(']');return out;}
  if(c=='"')return string();
  for(String literal:new String[]{"true","false","null"})if(text.startsWith(literal,at)){at+=literal.length();return literal.equals("null")?null:literal.equals("true");}
  int start=at;while(at<text.length()&&"-+0123456789.eE".indexOf(text.charAt(at))>=0){if(at-start>=128)throw bad();at++;}String number=text.substring(start,at);
  if(!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();
  int exponent=number.indexOf('e');if(exponent<0)exponent=number.indexOf('E');
  if(exponent>=0){int digits=exponent+1;if(number.charAt(digits)=='+'||number.charAt(digits)=='-')digits++;if(number.length()-digits>4)throw bad();}
  boolean nonzero=false;for(int i=0,end=exponent<0?number.length():exponent;i<end;i++){char digit=number.charAt(i);if(digit>='1'&&digit<='9'){nonzero=true;break;}}
  // Both signs of nonzero underflow are invalid; exact zero, including -0.0, remains valid.
  try{if(!number.contains(".")&&exponent<0)return Long.valueOf(number);double d=Double.parseDouble(number);if(!Double.isFinite(d)||(d==0&&nonzero))throw bad();return d;}catch(NumberFormatException e){throw bad();}
 }
 private String string()throws IOException{
  if(at>=text.length()||text.charAt(at++)!='"')throw bad();StringBuilder s=new StringBuilder();
  while(at<text.length()){char c=text.charAt(at++);if(c=='"'){String out=s.toString();for(int i=0;i<out.length();i++){char v=out.charAt(i);if(Character.isHighSurrogate(v)){if(++i>=out.length()||!Character.isLowSurrogate(out.charAt(i)))throw bad();}else if(Character.isLowSurrogate(v))throw bad();}return out;}if(c<32)throw bad();
   if(c=='\\'){if(at>=text.length())throw bad();char e=text.charAt(at++);if(e=='u'){if(at+4>text.length())throw bad();String hex=text.substring(at,at+4);if(!hex.matches("[0-9a-fA-F]{4}"))throw bad();s.append((char)Integer.parseInt(hex,16));at+=4;}else{int index="\"\\/bfnrt".indexOf(e);if(index<0)throw bad();s.append(new char[]{'"','\\','/','\b','\f','\n','\r','\t'}[index]);}}else s.append(c);
  }throw bad();
 }
 static String canonical(Object v)throws IOException{
  if(v instanceof Map){Map<String,Object> map=(Map<String,Object>)v;List<String> keys=new ArrayList<>(map.keySet());Collections.sort(keys);List<String> parts=new ArrayList<>();for(String key:keys)parts.add(quote(key)+":"+canonical(map.get(key)));return "{"+String.join(",",parts)+"}";}
  if(v instanceof List){List<String> parts=new ArrayList<>();for(Object item:(List<?>)v)parts.add(canonical(item));return "["+String.join(",",parts)+"]";}
  if(v instanceof String)return quote((String)v);if(v instanceof Long||v instanceof Boolean)return v.toString();if(v==null)return "null";throw bad();
 }
 private static String quote(String s)throws IOException{StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)throw bad();else b.append(c);}return b.append('"').toString();}
}
