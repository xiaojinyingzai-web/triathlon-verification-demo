package com.triathlon.prep.core;

import java.net.URI;import java.nio.charset.Charset;import java.util.*;
/** Public parameters only. This draft is never an execution authorization or credentials container. */
public final class DeviceConfigDraft {
 public enum Group { DEVICE,READER,CAMERA,PRINTER,FACE,TIMING,IMAGES }
 public enum Kind { TEXT,INTEGER,DECIMAL,CHOICE }
 public enum Field {
  DEVICE_NUMBER(Group.DEVICE,"设备编号",128),DEVICE_NAME(Group.DEVICE,"设备名称",128),CHECKIN_PROFILE(Group.DEVICE,"报到核验能力","FULL","SCORES_ONLY_NO_PRINT"),
  READER_TRANSPORT(Group.READER,"读卡方式","USB","SAM_USB","SAM_SERIALPORT"),SERIAL_PATH(Group.READER,"读卡串口路径",128),SERIAL_BAUD(Group.READER,"串口波特率",1,Integer.MAX_VALUE),
  CAMERA_ID(Group.CAMERA,"相机ID",128),PREVIEW_WIDTH(Group.CAMERA,"预览宽度",1,8192),PREVIEW_HEIGHT(Group.CAMERA,"预览高度",1,8192),JPEG_WIDTH(Group.CAMERA,"照片宽度",1,8192),JPEG_HEIGHT(Group.CAMERA,"照片高度",1,8192),
  CAMERA_DISPLAY_ROTATION(Group.CAMERA,"显示旋转角度","0","90","180","270"),CAMERA_DEVICE_ROTATION(Group.CAMERA,"设备顺时针角度","0","90","180","270"),EXTERNAL_ROTATION(Group.CAMERA,"外部旋转角度（可空）","0","90","180","270"),
  PRINTER_VENDOR_ID(Group.PRINTER,"USB vendor ID",1,65535),PRINTER_PRODUCT_ID(Group.PRINTER,"USB product ID",1,65535),PRINTER_DOTS(Group.PRINTER,"打印点宽",1,2048),PRINTER_ENCODING(Group.PRINTER,"打印编码",40),LATIN_GLYPH_DOTS(Group.PRINTER,"拉丁字形点宽",1,2048),WIDE_GLYPH_DOTS(Group.PRINTER,"宽字形点宽",1,2048),
  FACE_ENDPOINT(Group.FACE,"服务地址",256),BYTE_FORMAT(Group.FACE,"图像字节格式","SIGNED","UNSIGNED"),SIGN_CASE(Group.FACE,"签名大小写","LOWER","UPPER"),
  LIVE_THRESHOLD(Group.FACE,"活体阈值",Kind.DECIMAL,0,100),LIVE_RULE(Group.FACE,"活体阈值方向","AT_LEAST","AT_MOST"),FACE_THRESHOLD(Group.FACE,"人脸阈值",Kind.DECIMAL,0,100),FACE_RULE(Group.FACE,"人脸阈值方向","AT_LEAST","AT_MOST"),
  READER_TIMEOUT(Group.TIMING,"读卡超时毫秒",1,300000),STAGE_TIMEOUT(Group.TIMING,"核验阶段超时毫秒",1,300000),PRINT_TIMEOUT(Group.TIMING,"打印超时毫秒",1,300000),FACE_TIMEOUT(Group.TIMING,"服务超时毫秒",1,300000),TOKEN_TTL(Group.TIMING,"token有效期毫秒",1,1800000),PREVIEW_STABLE(Group.TIMING,"预览稳定毫秒",0,300000),PREVIEW_FRESH(Group.TIMING,"预览新鲜毫秒",1,5000),
  MAX_IMAGE_BYTES(Group.IMAGES,"服务图像最大字节",1,8*1024*1024),EXTERNAL_MAX_EDGE(Group.IMAGES,"导出图像最大边长",1,4096),EXTERNAL_JPEG_QUALITY(Group.IMAGES,"导出JPEG质量",0,100),EXTERNAL_MAX_BYTES(Group.IMAGES,"导出图像最大字节",1,8*1024*1024);
  public final Group group;public final String label;public final Kind kind;public final int maxLength;public final double minimum,maximum;public final List<String> choices;
  Field(Group g,String label,int length){this(g,label,Kind.TEXT,length,0,0,new String[0]);}
  Field(Group g,String label,int min,int max){this(g,label,Kind.INTEGER,32,min,max,new String[0]);}
  Field(Group g,String label,Kind kind,double min,double max){this(g,label,kind,32,min,max,new String[0]);}
  Field(Group g,String label,String...choices){this(g,label,Kind.CHOICE,32,0,0,choices);}
  Field(Group g,String label,Kind kind,int length,double min,double max,String[] choices){group=g;this.label=label;this.kind=kind;maxLength=length;minimum=min;maximum=max;this.choices=Collections.unmodifiableList(Arrays.asList(choices.clone()));}
 }
 public static final class Review {
  public final boolean validForSave,parametersComplete;public final boolean executionEnabled=false;
  public final List<String> missing,errors;
  private Review(List<String> missing,List<String> errors){this.missing=Collections.unmodifiableList(missing);this.errors=Collections.unmodifiableList(errors);validForSave=errors.isEmpty();parametersComplete=missing.isEmpty()&&errors.isEmpty();}
 }
 private final EnumMap<Field,String> values;
 private DeviceConfigDraft(EnumMap<Field,String> values){this.values=new EnumMap<>(values);}
 public static DeviceConfigDraft empty(){return new DeviceConfigDraft(new EnumMap<>(Field.class));}
 public static DeviceConfigDraft from(Map<String,String> input){if(input==null)throw new IllegalArgumentException("Public draft required");EnumMap<Field,String> values=new EnumMap<>(Field.class);for(Map.Entry<String,String> e:input.entrySet()){Field field;try{field=Field.valueOf(e.getKey());}catch(RuntimeException bad){throw new IllegalArgumentException("Unknown/secret/activation field is not supported");}if(e.getValue()==null||e.getValue().length()>1024)throw new IllegalArgumentException("Bounded public value required");values.put(field,e.getValue());}return new DeviceConfigDraft(values);}
 public String value(Field field){if(field==null)throw new IllegalArgumentException();String v=values.get(field);return v==null?"":v;}
 public DeviceConfigDraft with(Field field,String value){if(field==null||value==null||value.length()>1024)throw new IllegalArgumentException("Bounded public value required");EnumMap<Field,String> changed=new EnumMap<>(values);changed.put(field,value);return new DeviceConfigDraft(changed);}
 public Map<String,String> toMap(){Map<String,String> out=new LinkedHashMap<>();for(Field f:Field.values())out.put(f.name(),value(f));return Collections.unmodifiableMap(out);}
 public boolean scoresOnly(){return "SCORES_ONLY_NO_PRINT".equals(value(Field.CHECKIN_PROFILE).trim());}
 public Review review(){List<String> missing=new ArrayList<>(),errors=new ArrayList<>();boolean serial=value(Field.READER_TRANSPORT).equals("SAM_SERIALPORT");
  for(Field f:Field.values()){String raw=value(f),v=raw.trim();boolean optional=f==Field.CHECKIN_PROFILE||f==Field.EXTERNAL_ROTATION||!serial&&(f==Field.SERIAL_PATH||f==Field.SERIAL_BAUD)||scoresOnly()&&(f.group==Group.PRINTER||f==Field.PRINT_TIMEOUT||f==Field.LIVE_THRESHOLD||f==Field.LIVE_RULE||f==Field.FACE_THRESHOLD||f==Field.FACE_RULE);if(v.isEmpty()){if(!optional)missing.add(f.label);continue;}boolean valid=raw.length()<=f.maxLength&&!raw.matches("(?s).*[\\p{Cntrl}].*");
   if(valid)try{if(f.kind==Kind.INTEGER){valid=v.matches("[0-9]+")&&Long.parseLong(v)>=f.minimum&&Long.parseLong(v)<=f.maximum;}else if(f.kind==Kind.DECIMAL){double n=Double.parseDouble(v);valid=Double.isFinite(n)&&n>=f.minimum&&n<=f.maximum;}else if(f.kind==Kind.CHOICE)valid=f.choices.contains(v);
    if(f==Field.SERIAL_PATH)valid=valid&&v.startsWith("/dev/")&&v.length()>5;
    if(f==Field.PRINTER_ENCODING)valid=valid&&Charset.isSupported(v);
    if(f==Field.FACE_ENDPOINT){URI u=new URI(v);valid=valid&&(u.getScheme().equals("http")||u.getScheme().equals("https"))&&"yfs4.sfzydq.com".equals(u.getHost())&&u.getPort()==8080&&"/yjm/decode/v3/http".equals(u.getPath())&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null;}
   }catch(Exception bad){valid=false;}if(!valid)errors.add(f.label+"格式或范围不合法");
  }
  try{if(present(Field.PREVIEW_STABLE)&&present(Field.STAGE_TIMEOUT)&&number(Field.PREVIEW_STABLE)>=number(Field.STAGE_TIMEOUT))errors.add("预览稳定时间必须小于阶段超时");if(present(Field.PRINTER_DOTS)&&present(Field.LATIN_GLYPH_DOTS)&&present(Field.WIDE_GLYPH_DOTS)){long width=number(Field.PRINTER_DOTS),latin=number(Field.LATIN_GLYPH_DOTS),wide=number(Field.WIDE_GLYPH_DOTS);if(wide<latin||wide>width/4)errors.add("打印字形点宽与纸宽不兼容");}}catch(NumberFormatException ignored){/* Individual numeric errors already diagnosed. */}
  return new Review(missing,errors);
 }
 private boolean present(Field f){return !value(f).trim().isEmpty();}private long number(Field f){return Long.parseLong(value(f).trim());}
 @Override public boolean equals(Object o){return o instanceof DeviceConfigDraft&&toMap().equals(((DeviceConfigDraft)o).toMap());}
 @Override public int hashCode(){return toMap().hashCode();}
}
