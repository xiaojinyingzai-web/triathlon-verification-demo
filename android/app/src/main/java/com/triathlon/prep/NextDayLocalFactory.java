package com.triathlon.prep;
import android.content.Context;import com.triathlon.prep.core.*;import com.triathlon.prep.core.nextday.*;import com.triathlon.prep.nextday.host.NextDayModule;import com.triathlon.prep.nextday.storage.*;import com.triathlon.prep.nextday.records.NextDayRecordStore;
import javax.crypto.SecretKey;import java.io.*;
final class NextDayLocalFactory {
 static NextDayModule open(Context app,FaceLibrary.Scope scope,FaceEngine engine,CapturePort capture,FaceEngine.RuntimeGate gate,QualifiedSceneSlot scenes,VerificationModeSession authority,Verification.Clock clock,NextDaySurfaceTarget surface)throws IOException{
  File folder=new File(new File(new File(new File(app.getNoBackupFilesDir(),"nextday"),scope.mode.name()),scope.batchId),scope.modelFingerprint);
  if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Private next-day folder unavailable");
  SecretKey key=FaceLibraryKeyStore.loadOrCreate(app,scope.mode);SqliteFaceLibraryStore store=null;
  try{store=new SqliteFaceLibraryStore(new File(folder,"library.db"),key);NextDayRecordStore records=new NextDayRecordStore(new File(folder,"verification.dat"),key,scope.mode);
   return new NextDayModule(scope,store,engine,capture,records,scenes,authority,clock,gate,surface::revision);
  }catch(IOException|RuntimeException error){if(store!=null)store.close();try{engine.close();}catch(IOException ignored){}try{capture.close();}catch(IOException ignored){}throw error;}
 }
}
