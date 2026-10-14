package com.triathlon.prep;
import android.app.Activity;import android.app.Application;import android.os.*;import android.view.WindowManager;import java.io.File;
import com.triathlon.prep.core.*;import com.triathlon.prep.cefk.hardware.*;import com.csht.readcard.ReadCardManager;import com.csht.enums.ReadCardType;

/** Independent debug host. Default pending service review never creates or initializes the SDK. */
public class DebugReaderOnlyActivity extends Activity {
 private ReaderOnlyContract.Panel panel;private DebugReaderOnlyCoordinator coordinator;private boolean foreground,exiting;private long epoch;
 private final Handler main=new Handler(Looper.getMainLooper());
 private ReaderServiceApproval approval;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  try{
   ReaderOnlyContract.PanelFactory factory=(ReaderOnlyContract.PanelFactory)Class.forName("com.triathlon.prep.ui.DebugReaderOnlyPanel").getDeclaredConstructor().newInstance();
   coordinator=createCoordinator(model->{long ticket=epoch;main.post(()->{if(panel==null||ticket!=epoch||!coordinator.currentModel(model))return;ReaderOnlyContract.Model visible=foreground&&!exiting?model:new ReaderOnlyContract.Model(model.phase,"","","","","",model.code);panel.render(visible);});});
   panel=factory.create(this,new ReaderOnlyContract.Actions(){
    public void prepare(){if(foreground&&!exiting){epoch++;coordinator.prepare();}}
    public void readOnce(){if(foreground&&!exiting){coordinator.readOnce();}}
    public void cancel(){cancelReader();}
    public void exit(){exiting=true;cancelReader();finish();}
   });setContentView(panel.view());panel.render(new ReaderOnlyContract.Model(ReaderOnlyContract.Phase.NEEDS_PRIVATE_INPUT,"","","","","","NEEDS_PRIVATE_INPUT"));
  }catch(Exception error){if(coordinator!=null)coordinator.close();finish();}
 }
 protected DebugReaderOnlyCoordinator createCoordinator(java.util.function.Consumer<ReaderOnlyContract.Model> output){
  return new DebugReaderOnlyCoordinator(new DebugReaderOnlyCoordinator.Provider(){
   public ReaderDiagnosticInput input()throws Exception{return ReaderDiagnosticInbox.consume(DebugReaderOnlyActivity.this);}
   public RaceRoster roster()throws Exception{approval=ReaderDiagnosticInbox.approval(DebugReaderOnlyActivity.this);return new FileRosterStore(new File(getFilesDir(),"race-roster")).current();}
   public boolean serviceApproved(){return serviceExecutionApproved();}
   public CefkCardReader.Backend reader(ReaderDiagnosticInput input){return input.use((id,key,secret)->{
    // Reached only after the coordinator's explicit approval gate, never by prepare().
    ReadCardManager.init((Application)getApplicationContext());
    return new CshtReaderFactory(getApplicationContext(),new CshtReaderFactory.Configuration(ReadCardType.USB,null,0,id,key,secret,null,ReaderServiceApproval.HOST,ReaderServiceApproval.PORT)).create();
   });}
  },output);
 }
 /** No automatic grant: only a current exact-APK operator approval enables the explicit read action. */
 protected boolean serviceExecutionApproved(){return approval!=null&&approval.validAt(System.currentTimeMillis());}
 private void cancelReader(){epoch++;if(panel!=null)panel.clearPersonalResult();if(coordinator!=null)coordinator.cancel();}
 @Override protected void onResume(){super.onResume();foreground=true;}
 @Override protected void onPause(){foreground=false;cancelReader();super.onPause();}
 @Override protected void onDestroy(){exiting=true;cancelReader();if(coordinator!=null)coordinator.close();super.onDestroy();}
 @Override public void onBackPressed(){exiting=true;cancelReader();super.onBackPressed();}
}
