package com.triathlon.prep;
import android.graphics.*;
import java.io.ByteArrayOutputStream;
/** Internal normalized PNG -> configurable transport JPEG. Defaults are user-authorized, not vendor evidence. */
public final class ExternalPhoto implements CefkFaceAdapter.ImageEncoder {
 public static final com.triathlon.prep.cefk.face.FaceClient.ByteFormat DEFAULT_BYTE_FORMAT=com.triathlon.prep.cefk.face.FaceClient.ByteFormat.SIGNED;
 private static final class WipingBytes extends ByteArrayOutputStream { public void close(){java.util.Arrays.fill(buf,(byte)0);reset();} }
 public static final class Config {
  public final int maxEdge,jpegQuality,maxBytes;
  public Config(int maxEdge,int jpegQuality,int maxBytes){
   if(maxEdge<1||maxEdge>4096||jpegQuality<0||jpegQuality>100||maxBytes<1||maxBytes>8*1024*1024)throw new IllegalArgumentException("Explicit local image bounds required");
   this.maxEdge=maxEdge;this.jpegQuality=jpegQuality;this.maxBytes=maxBytes;
  }
  public static Config defaults(){return new Config(1280,90,8*1024*1024);}
 }
 private final Config config;
 public ExternalPhoto(Config config){if(config==null)throw new IllegalArgumentException();this.config=config;}
 public byte[] encode(byte[] png)throws Exception {
  if(png==null||png.length<8||png.length>8*1024*1024||png[0]!=(byte)137||png[1]!=80||png[2]!=78||png[3]!=71)throw new IllegalArgumentException("Normalized PNG required");
  Bitmap source=null,scaled=null,rgb=null;
  try{
   BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,bounds);
   if(bounds.outWidth<1||bounds.outHeight<1||(long)bounds.outWidth*bounds.outHeight>4L*1024*1024)throw new IllegalArgumentException("Image dimensions invalid");
   source=BitmapFactory.decodeByteArray(png,0,png.length);if(source==null)throw new IllegalArgumentException("Image cannot decode");
   float ratio=Math.min(1f,(float)config.maxEdge/Math.max(source.getWidth(),source.getHeight()));
   scaled=Bitmap.createScaledBitmap(source,Math.max(1,Math.round(source.getWidth()*ratio)),Math.max(1,Math.round(source.getHeight()*ratio)),true);
   rgb=Bitmap.createBitmap(scaled.getWidth(),scaled.getHeight(),Bitmap.Config.RGB_565);Canvas canvas=new Canvas(rgb);canvas.drawColor(Color.WHITE);canvas.drawBitmap(scaled,0,0,null);
   try(WipingBytes bytes=new WipingBytes()){
    if(!rgb.compress(Bitmap.CompressFormat.JPEG,config.jpegQuality,bytes))throw new IllegalArgumentException("JPEG encoding failed");
    if(bytes.size()<4||bytes.size()>config.maxBytes)throw new IllegalArgumentException("Configured local byte limit exceeded");
    return bytes.toByteArray();
   }
  }finally{if(rgb!=null)rgb.recycle();if(scaled!=null&&scaled!=source)scaled.recycle();if(source!=null)source.recycle();}
 }
}
