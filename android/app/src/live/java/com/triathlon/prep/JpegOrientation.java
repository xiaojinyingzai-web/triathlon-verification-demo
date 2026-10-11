package com.triathlon.prep;
/** Honor actual EXIF; a request is not evidence that the HAL rotated its pixels. */
final class JpegOrientation{
 static int rotation(int exif,int width,int height,int configuredWidth,int configuredHeight,int requested)throws CameraModule.Failure{
  if(!CameraModule.angle(requested)||!((width==configuredWidth&&height==configuredHeight)||(width==configuredHeight&&height==configuredWidth)))
   throw new CameraModule.Failure(CameraModule.Error.INVALID_IMAGE);
  if(exif==1)return 0;
  if(exif==3)return 180;
  if(exif==6)return 90;
  if(exif==8)return 270;
  if(exif==0&&requested==0&&width==configuredWidth&&height==configuredHeight)return 0;
  if(exif==0&&(requested==90||requested==270)&&configuredWidth!=configuredHeight&&width==configuredHeight&&height==configuredWidth)return 0;
  // Missing 180°/square-image EXIF or mirrored orientation cannot be inferred safely from size.
  throw new CameraModule.Failure(CameraModule.Error.INVALID_IMAGE);
 }
}
