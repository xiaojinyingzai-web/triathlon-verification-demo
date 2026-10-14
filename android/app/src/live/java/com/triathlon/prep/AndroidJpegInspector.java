package com.triathlon.prep;
import android.graphics.*;
import android.media.ExifInterface;
import android.media.FaceDetector;
import java.io.*;
/** Standard framework image checks only. Neither identity comparison nor liveness is done here. */
public final class AndroidJpegInspector implements StandardCameraCapture.Inspector {
 public StandardCameraCapture.Image inspect(byte[] jpeg,CameraModule.Config config,int requestedRotation)throws Exception{
  Bitmap source=null,upright=null,detectorImage=null;
  try{
   if(jpeg==null||jpeg.length<4||jpeg.length>8*1024*1024||config==null)throw invalid();
   BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
   BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
   if(!"image/jpeg".equals(bounds.outMimeType)||bounds.outWidth<2||bounds.outHeight<2||(long)bounds.outWidth*bounds.outHeight>4_000_000)throw invalid();
   ExifInterface exif=new ExifInterface(new ByteArrayInputStream(jpeg));
   int rotate=JpegOrientation.rotation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_UNDEFINED),
    bounds.outWidth,bounds.outHeight,config.jpegWidth,config.jpegHeight,requestedRotation);
   BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
   source=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);if(source==null)throw invalid();
   if(rotate==0)upright=source;
   else{Matrix matrix=new Matrix();matrix.setRotate(rotate);upright=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);}
   // FaceDetector's standard contract requires RGB565 and an even width. Reject ambiguity; do not crop a face.
   if(upright.getWidth()%2!=0)throw invalid();
   detectorImage=upright.copy(Bitmap.Config.RGB_565,false);if(detectorImage==null)throw invalid();
   FaceDetector.Face[] faces=new FaceDetector.Face[2];
   int count=new FaceDetector(detectorImage.getWidth(),detectorImage.getHeight(),2).findFaces(detectorImage,faces);
   if(count<0||count>2)throw invalid();
   boolean quality=true;
   if(count==1){FaceDetector.Face face=faces[0];quality=face!=null&&Float.isFinite(face.confidence())&&face.confidence()>=FaceDetector.Face.CONFIDENCE_THRESHOLD
     &&Float.isFinite(face.eyesDistance())&&face.eyesDistance()>0;}
   try(ErasingOutput out=new ErasingOutput()){
    if(!upright.compress(Bitmap.CompressFormat.PNG,100,out)||out.size()<8||out.size()>8*1024*1024)throw invalid();
    byte[] png=out.toByteArray();try{return new StandardCameraCapture.Image(png,count,quality,upright.getWidth(),upright.getHeight());}finally{java.util.Arrays.fill(png,(byte)0);}
   }
  }finally{
   if(detectorImage!=null)detectorImage.recycle();
   if(upright!=null&&upright!=source)upright.recycle();
   if(source!=null)source.recycle();
  }
 }
 private static CameraModule.Failure invalid(){return new CameraModule.Failure(CameraModule.Error.INVALID_IMAGE);}
 private static final class ErasingOutput extends ByteArrayOutputStream{
  public void close(){java.util.Arrays.fill(buf,(byte)0);reset();}
 }
}
