package com.triathlon.prep;

import android.content.Context;
import android.app.Application;
import com.csht.readcard.ReadCardManager;
import com.triathlon.prep.core.*;
import com.triathlon.prep.intake.*;
import com.triathlon.prep.cefk.face.*;
import com.triathlon.prep.cefk.hardware.*;
import java.nio.charset.Charset;
import java.net.URI;
import java.util.function.Consumer;

/** CEFK-only production assembly. Historical Telpo/Baidu sources are excluded from this graph. */
public final class LiveRuntime {
 private LiveRuntime(){}
 public static final class PrinterConfig {
  public final int productId,vendorId,dotWidth;public final String encoding;
  public final CheckInReceipt.Layout layout;
  public PrinterConfig(int productId,int vendorId,int dotWidth,String encoding){
   this(productId,vendorId,dotWidth,encoding,null);
  }
  public PrinterConfig(int productId,int vendorId,int dotWidth,String encoding,CheckInReceipt.Layout layout){
   if(productId<1||productId>65535||vendorId<1||vendorId>65535||dotWidth<1||dotWidth>2048||encoding==null||!Charset.isSupported(encoding))throw new IllegalArgumentException("Confirmed printer configuration required");
   this.productId=productId;this.vendorId=vendorId;this.dotWidth=dotWidth;this.encoding=encoding;
   if(layout!=null&&layout.printableDots!=dotWidth)throw new IllegalArgumentException("Receipt width must match configured printer dots");this.layout=layout;
  }
 }
 public static final class Config {
  public final CshtReaderFactory.Configuration reader;public final CefkDeviceAdapter.CapturePort capture;
  public final CameraModule.Config standardCamera;
  public final long previewStableMillis,previewFreshMillis;
  public final ExternalPhoto.Config externalPhoto;
  public final FaceClient.Config face;public final FaceClient.CredentialsProvider credentials;public final CefkFaceAdapter.Parameters decision;
  public final PrinterConfig printer;public final long readerTimeoutMillis,stageTimeoutMillis,printTimeoutMillis;
  public final boolean executionEnabled,offlineReaderConfirmed,dependenciesConfirmed,usbNetworkConfirmed;
  public Config(CshtReaderFactory.Configuration reader,CefkDeviceAdapter.CapturePort capture,FaceClient.Config face,
    FaceClient.CredentialsProvider credentials,CefkFaceAdapter.Parameters decision,PrinterConfig printer,
    long readerTimeoutMillis,long stageTimeoutMillis,long printTimeoutMillis,boolean enabled,boolean offlineConfirmed,boolean dependenciesConfirmed){
   this(reader,capture,face,credentials,decision,printer,readerTimeoutMillis,stageTimeoutMillis,printTimeoutMillis,enabled,offlineConfirmed,dependenciesConfirmed,null);
  }
  public Config(CshtReaderFactory.Configuration reader,CefkDeviceAdapter.CapturePort capture,FaceClient.Config face,
    FaceClient.CredentialsProvider credentials,CefkFaceAdapter.Parameters decision,PrinterConfig printer,
    long readerTimeoutMillis,long stageTimeoutMillis,long printTimeoutMillis,boolean enabled,boolean offlineConfirmed,boolean dependenciesConfirmed,CameraModule.Config standardCamera){
   this(reader,capture,face,credentials,decision,printer,readerTimeoutMillis,stageTimeoutMillis,printTimeoutMillis,enabled,offlineConfirmed,dependenciesConfirmed,standardCamera,1000,500,ExternalPhoto.Config.defaults());
  }
  public Config(CshtReaderFactory.Configuration reader,CefkDeviceAdapter.CapturePort capture,FaceClient.Config face,
    FaceClient.CredentialsProvider credentials,CefkFaceAdapter.Parameters decision,PrinterConfig printer,
    long readerTimeoutMillis,long stageTimeoutMillis,long printTimeoutMillis,boolean enabled,boolean offlineConfirmed,boolean dependenciesConfirmed,CameraModule.Config standardCamera,long stableMillis,long freshMillis,ExternalPhoto.Config externalPhoto){
   this(reader,capture,face,credentials,decision,printer,readerTimeoutMillis,stageTimeoutMillis,printTimeoutMillis,enabled,offlineConfirmed,dependenciesConfirmed,standardCamera,stableMillis,freshMillis,externalPhoto,false);
  }
  public Config(CshtReaderFactory.Configuration reader,CefkDeviceAdapter.CapturePort capture,FaceClient.Config face,
    FaceClient.CredentialsProvider credentials,CefkFaceAdapter.Parameters decision,PrinterConfig printer,
    long readerTimeoutMillis,long stageTimeoutMillis,long printTimeoutMillis,boolean enabled,boolean offlineConfirmed,boolean dependenciesConfirmed,CameraModule.Config standardCamera,long stableMillis,long freshMillis,ExternalPhoto.Config externalPhoto,boolean usbNetworkConfirmed){
   this.usbNetworkConfirmed=usbNetworkConfirmed;
   this.reader=reader;this.capture=capture;this.face=face;this.credentials=credentials;this.decision=decision;this.printer=printer;
   this.readerTimeoutMillis=readerTimeoutMillis;this.stageTimeoutMillis=stageTimeoutMillis;this.printTimeoutMillis=printTimeoutMillis;
   executionEnabled=enabled;offlineReaderConfirmed=offlineConfirmed;this.dependenciesConfirmed=dependenciesConfirmed;
   this.standardCamera=standardCamera;
   previewStableMillis=stableMillis;previewFreshMillis=freshMillis;this.externalPhoto=externalPhoto;
  }
 }
 public interface Factory { Components build(Config config,Verification.Clock clock); }
 public static final class Components {
  public final Verification.DeviceAdapter reader;public final Verification.AlgorithmAdapter algorithm;
  public final ReceiptPrintController.Transport printer;public final Runnable close;
  public final Consumer<Verification> bind;
  public final VerificationDisplay display;
  public final CardInbox inbox;public final AutoCardIntake.SourceFactory cardSource;
  public Components(Verification.DeviceAdapter reader,Verification.AlgorithmAdapter algorithm,ReceiptPrintController.Transport printer,Runnable close){this(reader,algorithm,printer,close,f->{});}
  public Components(Verification.DeviceAdapter reader,Verification.AlgorithmAdapter algorithm,ReceiptPrintController.Transport printer,Runnable close,Consumer<Verification> bind){this(reader,algorithm,printer,close,bind,null);}
  public Components(Verification.DeviceAdapter reader,Verification.AlgorithmAdapter algorithm,ReceiptPrintController.Transport printer,Runnable close,Consumer<Verification> bind,VerificationDisplay display){this(reader,algorithm,printer,close,bind,display,null,null);}
  public Components(Verification.DeviceAdapter reader,Verification.AlgorithmAdapter algorithm,ReceiptPrintController.Transport printer,Runnable close,Consumer<Verification> bind,VerificationDisplay display,CardInbox inbox,AutoCardIntake.SourceFactory cardSource){this.inbox=inbox;this.cardSource=cardSource;this.reader=reader;this.algorithm=algorithm;this.printer=printer;this.close=close;this.bind=bind;this.display=display;}
 }
 public static VerificationRuntime create(Context app,Config config,Verification.RecordStore store,Verification.Clock clock){return create(app,config,store,clock,null);}
 public static VerificationRuntime create(Context app,Config config,Verification.RecordStore store,Verification.Clock clock,Verification.RosterProvider roster){
  if(app==null||!ready(config))return unavailable(store,clock,roster);
  return assemble(config,new SdkFactory(app.getApplicationContext()),store,clock,roster);
 }
 private static boolean ready(Config c){
  if(c==null||!c.executionEnabled||!c.dependenciesConfirmed||c.reader==null
    ||(c.reader.type==com.csht.enums.ReadCardType.USB?!c.usbNetworkConfirmed:!c.offlineReaderConfirmed)||(c.capture==null&&c.standardCamera==null)||(c.capture!=null&&c.capture.mode()!=Verification.Mode.LIVE)
    ||c.face==null||c.credentials==null||c.decision==null||(!c.decision.confirmed&&!c.decision.scoresOnly)||(c.decision.scoresOnly&&c.printer!=null)
    ||c.readerTimeoutMillis<1||c.readerTimeoutMillis>300000||c.stageTimeoutMillis<1||c.stageTimeoutMillis>300000||c.printTimeoutMillis<1||c.printTimeoutMillis>300000
    ||c.externalPhoto==null||(c.capture==null&&(c.previewStableMillis<0||c.previewStableMillis>=c.stageTimeoutMillis||c.previewFreshMillis<1||c.previewFreshMillis>5000)))return false;
  FaceClient.Config f=c.face;
  if(f.mode!=FaceClient.Mode.REAL||!f.realUseAuthorized||!f.imageContractConfirmed||(!c.decision.scoresOnly&&!f.thresholdsConfirmed)
    ||f.byteFormat==null||f.byteFormat==FaceClient.ByteFormat.UNCONFIRMED||f.signCase==null||f.signCase==FaceClient.SignCase.UNCONFIRMED
    ||f.deviceName==null||f.deviceName.trim().isEmpty()||f.deviceNumber==null||f.deviceNumber.trim().isEmpty()
    ||f.timeoutMs<1||f.maxImageBytes<1||f.tokenTtlMs<1||f.tokenTtlMs>1800000)return false;
  try{
   URI u=new URI(f.endpoint);
   return ("http".equals(u.getScheme())||"https".equals(u.getScheme()))&&"yfs4.sfzydq.com".equals(u.getHost())&&u.getPort()==8080
     &&"/yjm/decode/v3/http".equals(u.getPath())&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null;
  }catch(Exception ex){return false;}
 }
 public static VerificationRuntime assemble(Config config,Factory factory,Verification.RecordStore store,Verification.Clock clock,Verification.RosterProvider roster){
  if(!ready(config)||factory==null)return unavailable(store,clock,roster);
  Components parts=null;
  try{
   parts=factory.build(config,clock);
   if(parts==null||parts.reader==null||parts.algorithm==null||parts.printer==null||parts.close==null||parts.bind==null
    ||parts.reader.mode()!=Verification.Mode.LIVE||parts.algorithm.mode()!=Verification.Mode.LIVE||parts.printer.mode()!=Verification.Mode.LIVE)throw new IllegalStateException("LIVE components required");
   Verification flow=new Verification(Verification.Mode.LIVE,parts.reader,parts.algorithm,store,clock,config.stageTimeoutMillis,roster);
   parts.bind.accept(flow);
   VerificationRuntime runtime=new VerificationRuntime(flow,true,parts.close,parts.display,parts.printer,clock,config.printTimeoutMillis,config.printer==null?null:config.printer.layout,parts.inbox,parts.cardSource);
   if(parts.algorithm instanceof CefkFaceAdapter)runtime.setDiagnosticSource(((CefkFaceAdapter)parts.algorithm)::failureDiagnostic);
   return runtime;
  }catch(RuntimeException|LinkageError ex){if(parts!=null&&parts.close!=null)try{parts.close.run();}catch(RuntimeException|LinkageError ignored){}return unavailable(store,clock,roster);}
 }
 private static final Object INIT_LOCK=new Object();private static boolean sdkInitialized;
 private static final class SdkFactory implements Factory{
  private final Context app;SdkFactory(Context app){this.app=app;}
  public Components build(Config c,Verification.Clock clock){
   CaptureOnlyDevice device=null;CefkFaceAdapter algorithm=null;CefkReceiptTransport printer=null;CefkDeviceAdapter.CapturePort capture=null;VerificationDisplay display=null;
   try{
    if(!(app instanceof Application))throw new IllegalStateException("Application required");
    synchronized(INIT_LOCK){if(!sdkInitialized){ReadCardManager.init((Application)app);sdkInitialized=true;}}
    if(c.capture==null)display=new VerificationDisplay(new DisplayPixels.Mapping(0,false),c.standardCamera.previewWidth,c.standardCamera.previewHeight);
    capture=c.capture!=null?c.capture:new PreviewCameraCapture(app,c.standardCamera,display,c.stageTimeoutMillis,c.previewStableMillis,c.previewFreshMillis);
    device=new CaptureOnlyDevice(capture);
    CardInbox inbox=new CardInbox(Verification.Mode.LIVE,device);
    AutoCardIntake.SourceFactory source=new CshtIntakeFactory(new CshtReaderFactory(app,c.reader));
    FaceClient client=new FaceClient(c.face,c.credentials,new FaceHttpTransport(c.face,new AndroidFaceJsonCodec(),65536),clock::wallMillis);
    algorithm=new CefkFaceAdapter(Verification.Mode.LIVE,client,c.decision,new ExternalPhoto(c.externalPhoto));
    if(c.printer!=null)printer=new CefkReceiptTransport(new CefkPrinter(new CshtPrinterPort(app,c.printer.productId,c.printer.vendorId),c.printer.dotWidth,c.printer.encoding));
    final CaptureOnlyDevice ownedDevice=device;final CefkFaceAdapter ownedAlgorithm=algorithm;final CefkReceiptTransport ownedPrinter=printer;
    ReceiptPrintController.Transport transport=printer==null?unavailablePrinter():printer;
    return new Components(display==null?inbox:new DisplayedDeviceAdapter(inbox,display),algorithm,transport,()->release(ownedDevice,ownedAlgorithm,ownedPrinter),printer==null?f->{}:printer::bind,display,inbox,source);
   }catch(RuntimeException|LinkageError ex){try{release(device,algorithm,printer);}finally{if(display!=null)display.close();if(device==null&&capture!=null)try{capture.close();}catch(Exception|LinkageError ignored){}}throw ex;}
  }
 }
 private static void release(CaptureOnlyDevice device,CefkFaceAdapter algorithm,CefkReceiptTransport printer){
  try{if(printer!=null)printer.close();}finally{try{if(algorithm!=null)algorithm.close();}finally{if(device!=null)try{device.close();}catch(Exception|LinkageError ignored){}}}
 }
 private static ReceiptPrintController.Transport unavailablePrinter(){return new ReceiptPrintController.Transport(){
  public Verification.Mode mode(){return Verification.Mode.LIVE;}
  public void print(String id,java.util.List<String> lines,ReceiptPrintController.Reply reply){reply.failed(ReceiptPrintController.Failure.NOT_CONNECTED);}
  public void cancel(String id){}
 };}
 private static VerificationRuntime unavailable(Verification.RecordStore store,Verification.Clock clock,Verification.RosterProvider roster){
  return new VerificationRuntime(new Verification(Verification.Mode.LIVE,Unconnected.device(),Unconnected.algorithm(),store,clock,15000,roster),false,()->{});
 }
}
