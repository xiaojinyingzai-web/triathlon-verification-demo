package com.triathlon.prep.nextday.baidu;

/** Explicit SDK asset/quality/liveness configuration. No guessed device thresholds or activation data. */
public final class BaiduV9Configuration {
 public final String modelFingerprint;final String[] detect,quality,live,feature;
 public final float minimumDetect,maximumBlur,minimumIllumination,maximumPose,maximumOcclusion,minimumLive;
 public final int minimumFaceSize,indexSuccessCode;public final float featureSuccessCode;public final long initializationTimeoutMillis;
 public BaiduV9Configuration(String fingerprint,String[] detect,String[] quality,String[] live,String[] feature,
    float detectScore,float blur,float illumination,float pose,float occlusion,float liveness,int minSize,int indexSuccess,float extractionSuccess,long timeout){
  if(fingerprint==null||!fingerprint.matches("[a-f0-9]{64}")||minSize<1||timeout<1||timeout>60000||!Float.isFinite(extractionSuccess))throw new IllegalArgumentException("Explicit SDK configuration required");
  for(float value:new float[]{detectScore,blur,illumination,pose,occlusion,liveness})if(!Float.isFinite(value)||value<0)throw new IllegalArgumentException("Explicit finite quality parameters required");
  modelFingerprint=fingerprint;this.detect=paths(detect,2,2);this.quality=paths(quality,2,2);this.live=paths(live,6,1);this.feature=paths(feature,4,3);
  minimumDetect=detectScore;maximumBlur=blur;minimumIllumination=illumination;maximumPose=pose;maximumOcclusion=occlusion;minimumLive=liveness;
  minimumFaceSize=minSize;indexSuccessCode=indexSuccess;featureSuccessCode=extractionSuccess;initializationTimeoutMillis=timeout;
 }
 private static String[] paths(String[] paths,int count,int required){if(paths==null||paths.length!=count)throw new IllegalArgumentException("Explicit SDK model paths required");String[] copy=paths.clone();for(int i=0;i<copy.length;i++){String path=copy[i];if(path==null||!path.equals(path.trim())||path.contains("..")||(i<required&&path.isEmpty()))throw new IllegalArgumentException("Explicit asset paths required");}return copy;}
}
