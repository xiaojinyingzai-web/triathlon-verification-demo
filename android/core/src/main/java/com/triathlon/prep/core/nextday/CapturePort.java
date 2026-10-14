package com.triathlon.prep.core.nextday;
import java.io.IOException;import java.util.Arrays;

/** Current-frame producer. Transport freshness/identity decisions belong to the owning workflow. */
public interface CapturePort extends AutoCloseable {
 Frame capture(String operationId,long epoch,long surfaceRevision)throws IOException;
 void cancel(String operationId);
 void close()throws IOException;
 enum ImageType { JPEG, NV21, PNG }
 final class Frame implements AutoCloseable {
  public final String operationId,frameId;public final long epoch,capturedAtElapsed,surfaceRevision;public final ImageType type;public final int width,height,rotation;public final boolean mirror;private byte[] image;
  public Frame(String operationId,long epoch,String frameId,long capturedAtElapsed,long surfaceRevision,ImageType type,int width,int height,int rotation,boolean mirror,byte[] image){
   if(!safe(operationId)||!safe(frameId)||epoch<1||capturedAtElapsed<0||surfaceRevision<0||type==null||width<1||height<1||width>4096||height>4096||(rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270)||image==null||image.length<1||image.length>8*1024*1024)throw new IllegalArgumentException("Invalid current frame");
   if(type==ImageType.NV21&&((width&1)!=0||(height&1)!=0||(long)width*height*3/2!=image.length))throw new IllegalArgumentException("NV21 dimensions mismatch");
   this.operationId=operationId;this.epoch=epoch;this.frameId=frameId;this.capturedAtElapsed=capturedAtElapsed;this.surfaceRevision=surfaceRevision;this.type=type;this.width=width;this.height=height;this.rotation=rotation;this.mirror=mirror;this.image=image.clone();
  }
  public synchronized byte[] image(){if(image==null)throw new IllegalStateException("Frame closed");return image.clone();}
  public synchronized boolean closed(){return image==null;}
  @Override public synchronized void close(){if(image!=null){Arrays.fill(image,(byte)0);image=null;}}
  private static boolean safe(String text){return text!=null&&text.matches("[A-Za-z0-9._:-]{1,128}");}
 }
}
