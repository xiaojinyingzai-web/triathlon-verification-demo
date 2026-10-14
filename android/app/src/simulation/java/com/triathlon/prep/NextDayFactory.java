package com.triathlon.prep;
import android.content.Context;import com.triathlon.prep.core.*;import com.triathlon.prep.core.nextday.*;import com.triathlon.prep.nextday.host.NextDayModule;import java.io.IOException;
/** Compiled only into TEST. All image/feature/liveness observations are synthetic byte markers. */
final class NextDayFactory {
 static String fingerprint(Context app){return "0000000000000000000000000000000000000000000000000000000000000000";}
 static NextDayModule create(Context app,FaceLibrary.Scope scope,QualifiedSceneSlot scenes,VerificationModeSession authority,Verification.Clock clock,NextDaySurfaceTarget surface)throws IOException{
  TestNextDayEngine engine=new TestNextDayEngine(scope);
  FaceEngine.RuntimeGate gate=new FaceEngine.RuntimeGate(){public boolean nativeInitializationAllowed(){return true;}public boolean licenceVerified(){return true;}public FaceEngine.Parameters parameters(){return engine.parameters();}};
  NextDayCapture capture=new NextDayCapture(){private boolean closed;private String prepared="";public void configure(){}public void preparePreview(String id,long epoch,long view){prepared=id;}public boolean previewReady(String id,long epoch,long view){return !closed&&prepared.equals(id);}public int bufferWidth(){return 0;}public int bufferHeight(){return 0;}public void onChanged(Runnable changed){}public Frame capture(String id,long epoch,long view)throws IOException{if(closed||!prepared.equals(id))throw new IOException("TEST capture not prepared");return new Frame(id,epoch,java.util.UUID.randomUUID().toString(),clock.elapsedMillis(),view,ImageType.JPEG,1,1,0,false,new byte[]{2});}public void cancel(String id){prepared="";}public void close(){closed=true;prepared="";}};
  return NextDayLocalFactory.open(app,scope,engine,capture,gate,scenes,authority,clock,surface);
 }
}
