package com.triathlon.prep;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.triathlon.prep.core.FileRecordStore;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.core.RaceRoster;
import com.triathlon.prep.core.FileRosterStore;
import com.triathlon.prep.core.RosterImportSession;
import com.triathlon.prep.core.ExcelRosterReader;
import com.triathlon.prep.ui.RecordsView;
import com.triathlon.prep.ui.DiagnosticsView;
import com.triathlon.prep.ui.SettingsView;
import com.triathlon.prep.ui.VerificationView;
import com.triathlon.prep.ui.RosterImportView;
import com.triathlon.prep.ui.ReceiptPreviewView;
import com.triathlon.prep.core.CheckInReceipt;
import com.triathlon.prep.core.ReceiptPrintController;
import com.triathlon.prep.core.AutoPrintPolicy;
import com.triathlon.prep.ui.PrintModeControl;
import com.triathlon.prep.ui.ReceiptNameControl;
import com.triathlon.prep.ui.DisplayRoleControl;
import com.triathlon.prep.ui.AthleteView;
import com.triathlon.prep.ui.AutomaticIntakeControl;
import com.triathlon.prep.intake.AutoCardIntake;
import com.triathlon.prep.intake.PreferencesCardFence;
import com.triathlon.prep.intake.TestCardSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;
import java.io.InputStream;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native screen host. Adapters remain selected exclusively by the build flavor. */
public final class MainActivity extends Activity {
    private Host host;
    private AndroidDisplayRoutes displayRoutes;
    private VerificationView screen;
    private DualDisplayWindows windows;
    private View staffRoot;
    private AthleteView athleteScreen;
    private DisplayRoleControl setupScreen;
    private DisplayRoleControl.Status roleStatus=DisplayRoleControl.Status.READY;
    private boolean staffAvailable;
    private String routeKey="";
    private VerificationDisplay activeDisplay;
    private RecordsView recordsScreen;
    private SettingsView settingsScreen;
    private DiagnosticsView diagnosticsScreen;
    private RosterImportView rosterScreen;
    private ReceiptPreviewView receiptScreen;
    private Button recordsEntry, settingsEntry,rosterEntry,receiptEntry;
    private LinearLayout navigation;
    private enum Page { MAIN, RECORDS, SETTINGS, DIAGNOSTICS, ROSTER, RECEIPT }
    private static final int PICK_ROSTER=1801;
    private static final int CAMERA_PERMISSION=2001;
    private VerificationView.PrimaryAction pendingCameraAction;
    private String pickerToken;
    private boolean visible;
    private boolean cameraPermissionAsked;
    private Page showingPage = Page.MAIN;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            syncWindows();
            renderRoles();
            if(!cameraPermissionAsked&&Adapters.mode()==Verification.Mode.LIVE&&host.mayRequestCamera()
                    &&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
                cameraPermissionAsked=true;pendingCameraAction=VerificationView.PrimaryAction.BEGIN;
                requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
            }
            host.refresh();
            main.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        displayRoutes=new AndroidDisplayRoutes(this);
        if (Adapters.mode() != Verification.Mode.TEST) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        Object retained = getLastNonConfigurationInstance();
        host = retained instanceof Host ? (Host) retained : new Host(getApplicationContext());
        if(state!=null&&state.getBoolean("neutralActivity",false)){host.neutralActivity=true;host.routeBlocked=true;}
        if(state!=null)pickerToken=state.getString("rosterPickerToken");
        windows=new DualDisplayWindows(this,this::createWindowContent,()->{
            displayRoutes.roles.clear();roleStatus=DisplayRoleControl.Status.FAILED;
            clearDisplay();host.unbindAndCancel();syncWindows();bindHost();
        });
        windows.activity(host.neutralActivity?DisplayWindowSession.Role.SETUP:DisplayWindowSession.Role.STAFF);
    }
    private View buildStaffView(Context uiContext) {
        screen = new VerificationView(uiContext, new VerificationView.Actions() {
            @Override public void onPrimaryAction(VerificationView.PrimaryAction action) {
                if (visible) {
                    if((action==VerificationView.PrimaryAction.BEGIN||action==VerificationView.PrimaryAction.RETAKE)
                            &&Adapters.mode()==Verification.Mode.LIVE&&host.mayRequestCamera()
                            &&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
                        pendingCameraAction=action;requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);return;
                    }
                    if(action==VerificationView.PrimaryAction.NEXT)clearDisplay();
                    if((action==VerificationView.PrimaryAction.BEGIN || action==VerificationView.PrimaryAction.RETAKE) && activeDisplay!=null)activeDisplay.arm();
                    host.act(action, false);
                }
            }
            @Override public void onCancel() {
                if (visible) {clearDisplay();host.act(VerificationView.PrimaryAction.NONE, true);}
            }
        },requested->{if(visible)host.changePrintMode(requested);},()->{if(visible)host.recheckCard();});
        recordsScreen = new RecordsView(uiContext, new RecordsView.Actions() {
            public void onBack() { if (visible) host.showMain(); }
            public void onFilterChanged(RecordsView.Filter filter) { if (visible) host.filter(filter); }
            public void onRecordSelected(String id) { if (visible) host.select(id); }
            public void onCloseDetail() { if (visible) host.select(null); }
        });
        settingsScreen = new SettingsView(uiContext, () -> { if (visible) host.showMain(); },
                () -> { if (visible) host.openDiagnostics(); },draft->{if(visible)host.changeReceiptName(draft);},this::assignDisplays);
        diagnosticsScreen = new DiagnosticsView(uiContext, new DiagnosticsView.Actions() {
            public void onBack() { if (visible) host.showSettings(); }
            public void onRefresh() { if (visible) host.refreshDiagnostics(); }
        });
        rosterScreen=new RosterImportView(uiContext,new RosterImportView.Actions(){
            public void onChooseFile(String event,String group){if(visible)host.beginChoice(event,group,token->main.post(()->{
                if(!visible||isDestroyed()){host.cancelChoice(token);return;}
                pickerToken=token;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"});
                try{startActivityForResult(pick,PICK_ROSTER);}catch(RuntimeException ex){pickerToken=null;host.cancelChoice(token);}
            }));}
            public void onConfirmImport(String id){if(visible)host.confirmRoster(id);}
            public void onDiscardPreview(String id){if(visible)host.discardRoster(id);}
            public void onErrorPageChanged(String id,int page){if(visible)host.rosterErrorPage(id,page);}
            public void onBack(){if(visible)host.leaveRoster();}
        });
        receiptScreen=new ReceiptPreviewView(uiContext,new ReceiptPreviewView.Actions(){
            public void onPrintRequested(String id){if(visible)host.printReceipt(id,false);}
            public void onRetryRequested(String id){if(visible)host.printReceipt(id,true);}
            public void onCancelRequested(String id,String job){if(visible)host.cancelPrint(id,job);}
            public void onBack(String id){if(visible)host.leaveReceipt(id);}
        });
        screen.render(VerificationPresenter.model(null, Adapters.mode(), null));
        LinearLayout root = new LinearLayout(uiContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff5f7fa);
        FrameLayout pages = new FrameLayout(uiContext);
        pages.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(recordsScreen, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(settingsScreen, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(diagnosticsScreen, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(rosterScreen,new FrameLayout.LayoutParams(-1,-1));
        pages.addView(receiptScreen,new FrameLayout.LayoutParams(-1,-1));
        recordsScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        diagnosticsScreen.setVisibility(View.GONE);
        rosterScreen.setVisibility(View.GONE);
        receiptScreen.setVisibility(View.GONE);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        recordsEntry = new Button(uiContext);
        recordsEntry.setText("核验记录");
        recordsEntry.setAllCaps(false);
        recordsEntry.setTextColor(0xff1769b7);
        recordsEntry.setEnabled(false);
        recordsEntry.setOnClickListener(view -> { if (visible) host.openRecords(); });
        settingsEntry = new Button(uiContext);
        settingsEntry.setText("设备与设置");
        settingsEntry.setAllCaps(false);
        settingsEntry.setTextColor(0xff1769b7);
        settingsEntry.setEnabled(false);
        settingsEntry.setOnClickListener(view -> { if (visible) host.openSettings(); });
        rosterEntry=new Button(uiContext);rosterEntry.setText("本场名单");rosterEntry.setAllCaps(false);rosterEntry.setTextColor(0xff1769b7);rosterEntry.setEnabled(false);
        rosterEntry.setOnClickListener(view->{if(visible)host.openRoster();});
        receiptEntry=new Button(uiContext);receiptEntry.setText("小票预览");receiptEntry.setAllCaps(false);receiptEntry.setTextColor(0xff1769b7);receiptEntry.setEnabled(false);
        receiptEntry.setOnClickListener(view->{if(visible)host.openReceipt();});
        navigation = new LinearLayout(uiContext);
        navigation.addView(recordsEntry, new LinearLayout.LayoutParams(0, -1, 1));
        navigation.addView(settingsEntry, new LinearLayout.LayoutParams(0, -1, 1));
        navigation.addView(rosterEntry,new LinearLayout.LayoutParams(0,-1,1));
        navigation.addView(receiptEntry,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(navigation, new LinearLayout.LayoutParams(-1,
                Math.round(48 * uiContext.getResources().getDisplayMetrics().density)));
        int barFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) barFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        root.setSystemUiVisibility(barFlags);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        staffRoot=root;staffAvailable=true;
        root.requestApplyInsets();
        return root;
    }

    @Override public void onStart() {
        super.onStart(); visible = true;
        displayRoutes.start();syncWindows();bindHost();
        main.post(ticker);
    }
    private void bindHost(){
        host.beforeAdmission=()->{clearDisplay();if(activeDisplay!=null)activeDisplay.arm();};
        host.bind((model, records, settings, diagnostics, page, canOpen, state, display,roster,receipt,printMode,receiptName,intakeModel) -> {
            if (!visible || isDestroyed()) return;
            renderAthlete(state);renderRoles();
            if(!staffAvailable)return;
            if(activeDisplay!=display || page!=Page.MAIN){clearDisplay();if(activeDisplay!=null)activeDisplay.detach();}
            activeDisplay=page==Page.MAIN?display:null;
            showingPage = page;
            screen.setVisibility(page == Page.MAIN ? View.VISIBLE : View.GONE);
            recordsScreen.setVisibility(page == Page.RECORDS ? View.VISIBLE : View.GONE);
            settingsScreen.setVisibility(page == Page.SETTINGS ? View.VISIBLE : View.GONE);
            diagnosticsScreen.setVisibility(page == Page.DIAGNOSTICS ? View.VISIBLE : View.GONE);
            rosterScreen.setVisibility(page==Page.ROSTER?View.VISIBLE:View.GONE);
            receiptScreen.setVisibility(page==Page.RECEIPT?View.VISIBLE:View.GONE);
            if(page!=Page.RECEIPT)receiptScreen.bindSession(null);
            navigation.setVisibility(page == Page.MAIN ? View.VISIBLE : View.GONE);
            recordsEntry.setEnabled(canOpen);
            settingsEntry.setEnabled(canOpen);
            rosterEntry.setEnabled(canOpen);
            receiptEntry.setEnabled(receipt!=null&&receipt.document!=null);
            if (page == Page.RECORDS) recordsScreen.render(records);
            else if (page == Page.SETTINGS) {settingsScreen.render(settings);settingsScreen.renderReceiptName(receiptName);settingsScreen.renderDisplayRoles(roleModel());}
            else if (page == Page.DIAGNOSTICS) diagnosticsScreen.render(diagnostics);
            else if(page==Page.ROSTER)rosterScreen.render(roster);
            else if(page==Page.RECEIPT&&receipt!=null){receiptScreen.bindSession(receipt.sessionId);receiptScreen.render(receipt);}
            else {
                screen.renderPrintMode(printMode);screen.renderAutomaticIntake(intakeModel);
                String id=state==null || state.sessionId.isEmpty() || state.stage==Verification.Stage.IDLE
                        || (state.result!=null && state.result.verdict==Verification.Verdict.CANCELLED)?null:state.sessionId;
                screen.bindSession(id);
                if(id==null)screen.render(model);else screen.render(id,model);
                if(display!=null) {
                    display.attach(host::refreshDisplay);
                    display.render(screen,id,id!=null,state!=null && state.stage==Verification.Stage.CAPTURING);
                }
            }
        });
    }
    @Override public void onBackPressed() {
        if(showingPage==Page.RECEIPT)host.leaveReceipt(null);
        else if(showingPage==Page.ROSTER)host.leaveRoster();
        else if (showingPage == Page.DIAGNOSTICS) host.showSettings();
        else if (showingPage != Page.MAIN) host.showMain();
        else super.onBackPressed();
    }
    @Override public void onStop() {
        pendingCameraAction=null;
        if(displayRoutes.roles.staff()!=null)host.routeBlocked=true;
        windows.close();displayRoutes.close();
        visible = false; main.removeCallbacks(ticker);clearDisplay();
        if(receiptScreen!=null)receiptScreen.bindSession(null);
        if(athleteScreen!=null)athleteScreen.bindSession(null);
        if(activeDisplay!=null){activeDisplay.detach();activeDisplay=null;}
        host.unbindAndCancel();
        super.onStop();
    }
    @Override public Object onRetainNonConfigurationInstance() { return host; }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("rosterPickerToken",pickerToken);state.putBoolean("neutralActivity",host.neutralActivity);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=PICK_ROSTER)return;
        String token=pickerToken;pickerToken=null;
        if(token==null)return;
        if(result==RESULT_OK&&data!=null&&data.getData()!=null)host.importFile(token,data.getData());else host.cancelChoice(token);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request!=CAMERA_PERMISSION)return;
        VerificationView.PrimaryAction action=pendingCameraAction;pendingCameraAction=null;
        if(!visible||showingPage!=Page.MAIN||action==null)return;
        if(grants.length==1&&grants[0]==PackageManager.PERMISSION_GRANTED&&host.mayRequestCamera()){
            if(activeDisplay!=null)activeDisplay.arm();host.act(action,false);
        }
        else host.cameraPermissionDenied();
    }
    @Override public void onDestroy() {
        if (!isChangingConfigurations()) host.close();
        super.onDestroy();
    }
    private void clearDisplay(){if(activeDisplay!=null)activeDisplay.clear();if(screen!=null)screen.bindSession(null);if(athleteScreen!=null)athleteScreen.bindSession(null);}
    private View createWindowContent(Context context,DisplayWindowSession.Role role){
        if(role==DisplayWindowSession.Role.STAFF){if(context==this)host.neutralActivity=false;clearDisplay();if(activeDisplay!=null){activeDisplay.detach();activeDisplay=null;}return buildStaffView(context);}
        if(role==DisplayWindowSession.Role.ATHLETE){if(context==this)host.neutralActivity=true;athleteScreen=new AthleteView(context);return athleteScreen;}
        staffAvailable=false;clearDisplay();if(activeDisplay!=null){activeDisplay.detach();activeDisplay=null;}
        setupScreen=new DisplayRoleControl(context,this::assignDisplays);
        android.widget.ScrollView scroll=new android.widget.ScrollView(context);scroll.setFillViewport(true);scroll.addView(setupScreen);return scroll;
    }
    private void syncWindows(){
        displayRoutes.refresh();
        String next=displayRoutes.roles.staff()+"/"+displayRoutes.roles.athlete();
        if(!routeKey.equals(next)){if(!routeKey.isEmpty()&&!routeKey.equals("null/null")&&next.equals("null/null"))host.routeBlocked=true;clearDisplay();routeKey=next;host.refreshDisplay();}
        if(!windows.sync(displayRoutes.roles))roleStatus=DisplayRoleControl.Status.FAILED;
        staffAvailable=staffRoot!=null&&staffRoot.isAttachedToWindow();
    }
    private void assignDisplays(Integer staff,Integer athlete){
        if(!visible||!host.mayAssignDisplays())return;
        roleStatus=DisplayRoleControl.Status.APPLYING;renderRoles();
        boolean valid=staff==null&&athlete==null;
        if(valid)displayRoutes.roles.clear();else if(staff!=null&&athlete!=null)valid=displayRoutes.select(staff,athlete);
        roleStatus=valid?DisplayRoleControl.Status.READY:DisplayRoleControl.Status.FAILED;
        if(valid){host.routeBlocked=staff==null;clearDisplay();syncWindows();host.refreshDisplay();}
        renderRoles();
    }
    private DisplayRoleControl.Model roleModel(){
        java.util.ArrayList<DisplayRoleControl.Option> options=new java.util.ArrayList<>();
        for(DisplayRoutes.Screen d:displayRoutes.roles.screens())options.add(new DisplayRoleControl.Option(d.id,d.name+" "+d.width+"×"+d.height));
        return new DisplayRoleControl.Model(options,displayRoutes.roles.staff(),displayRoutes.roles.athlete(),roleStatus,
            visible&&roleStatus!=DisplayRoleControl.Status.APPLYING&&host.mayAssignDisplays(),
            roleStatus==DisplayRoleControl.Status.FAILED?"窗口不可用或显示器已断开；请核对并重新明确分配。":"分配在当前运行期间有效；断开后需重新分配。真实设备触控与旋转待核对。");
    }
    private void renderRoles(){if(settingsScreen!=null)settingsScreen.renderDisplayRoles(roleModel());if(setupScreen!=null)setupScreen.render(roleModel());}
    private void renderAthlete(Verification.Snapshot state){
        if(athleteScreen==null)return;
        AthleteView.Model model=AthletePresenter.model(state,Adapters.mode(),host.waitingForCard());
        athleteScreen.bindSession(model.sessionId);athleteScreen.render(model);
    }

    private interface Sink {
        void show(VerificationView.Model model, RecordsView.Model records,
                SettingsView.Model settings, DiagnosticsView.Model diagnostics, Page page, boolean canOpen,
                Verification.Snapshot state,VerificationDisplay display,RosterImportView.Model roster,ReceiptPreviewView.Model receipt,PrintModeControl.Model printMode,ReceiptNameControl.Model receiptName,AutomaticIntakeControl.Model intakeModel);
                
    }

    /** One store/worker/core across pages and configuration recreation. */
    private static final class Host {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private Verification flow;
        private VerificationRuntime runtime;
        private AutoPrintPolicy autoPrint;
        private AutoCardIntake intake;
        private volatile Runnable beforeAdmission;
        private String businessKey="";
        private int syntheticIndex;
        private volatile boolean shuttingDown;
        private volatile boolean routeBlocked;
        private volatile boolean neutralActivity;
        private void intakeChanged(){if(!shuttingDown)try{worker.execute(()->{publishedKey=null;publish();});}catch(java.util.concurrent.RejectedExecutionException ignored){}}
        private void admitted(AutoCardIntake.Admission admission){
            Sink expected=sink;
            main.post(()->{
                if(shuttingDown||sink==null||sink!=expected){admission.close();return;}
                Runnable prepare=beforeAdmission;if(prepare!=null)prepare.run();
                try{worker.execute(()->acceptCard(admission,expected));}catch(java.util.concurrent.RejectedExecutionException ex){admission.close();}
            });
        }
        private void acceptCard(AutoCardIntake.Admission admission,Sink expected){
            if(sink==null||sink!=expected||page!=Page.MAIN||runtime==null||flow==null||!canChangePrintMode()||nameSaving.get()||modeSaving.get()){
                admission.close();if(intake!=null)intake.pause();return;
            }
            Verification.Snapshot old=flow.snapshot();
            if(old.stage!=Verification.Stage.IDLE&&old.saveState!=Verification.SaveState.SAVED){admission.close();intake.pause();return;}
            try{
                runtime.printing.cancel();
                if(old.stage==Verification.Stage.FINISHED)flow.next();
                if(!runtime.inbox.offer(admission)){admission.close();intake.pause();problem="接卡会话已失效，请由工作人员检查。";}
                else {autoPrint.begin(flow);businessKey="";}
            }catch(RuntimeException ex){admission.close();runtime.inbox.clear();intake.pause();problem="自动核验未开始，请检查本轮保存状态。";}
            publish();
        }
        private void syncIntake(){
            if(intake==null)return;
            boolean cameraReady=Adapters.mode()==Verification.Mode.TEST||app.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
            boolean allowed=sink!=null&&!routeBlocked&&cameraReady&&page==Page.MAIN&&currentRoster!=null&&!rosterCorrupt&&!nameSaving.get()&&!modeSaving.get();
            AutoCardIntake.Snapshot input=intake.snapshot();
            if(!allowed){if(input.state!=AutoCardIntake.State.PAUSED&&input.state!=AutoCardIntake.State.FAULT&&input.state!=AutoCardIntake.State.CLOSED)intake.pause();return;}
            Verification.Snapshot current=flow.snapshot();
            if(input.state==AutoCardIntake.State.PAUSED&&(current.stage==Verification.Stage.IDLE||current.stage==Verification.Stage.FINISHED&&current.saveState==Verification.SaveState.SAVED))intake.start();
            input=intake.snapshot();
            boolean saved=current.stage==Verification.Stage.FINISHED&&current.saveState==Verification.SaveState.SAVED;
            boolean sending=runtime.printing.snapshot().status==ReceiptPrintController.Status.SENDING;
            String key=input.admissionId+"/"+input.coreSessionId+"/"+current.sessionId+"/"+saved+"/"+sending+"/"+input.state;
            if(!key.equals(businessKey)){businessKey=key;intake.businessState(input.admissionId,current.sessionId,saved,sending);}
        }
        boolean waitingForCard(){
            AutoCardIntake i=intake;if(i==null)return false;
            AutoCardIntake.State state=i.snapshot().state;return state==AutoCardIntake.State.READY||state==AutoCardIntake.State.BLOCKED_SAME_CARD;
        }
        void recheckCard(){worker.execute(()->{if(intake!=null&&page==Page.MAIN&&canChangePrintMode()&&flow.snapshot().saveState!=Verification.SaveState.FAILED)intake.explicitReverify();publish();});}
        private AutomaticIntakeControl.Model intakeModel(){
            boolean test=Adapters.mode()==Verification.Mode.TEST;
            AutomaticIntakeControl.State state=AutomaticIntakeControl.State.DISABLED;
            String notice=currentRoster==null?"请先导入本场名单。":"未配置自动读卡；请核对设备与授权。";
            if(intake!=null){AutoCardIntake.Snapshot i=intake.snapshot();switch(i.state){
                case READY:state=flow.snapshot().stage==Verification.Stage.FINISHED?AutomaticIntakeControl.State.WAITING_NEXT:AutomaticIntakeControl.State.WAITING_CARD;notice=test?"测试按钮仅投递虚构卡；核验与保存由主流程自动完成。":"刷卡后自动核验，无需点击开始。";break;
                case BLOCKED_SAME_CARD:state=AutomaticIntakeControl.State.SAME_CARD;notice="重复卡未新建记录；如需重核，请由工作人员明确确认。";break;
                case BUSY:case ADMITTING:state=flow.snapshot().saveState==Verification.SaveState.FAILED?AutomaticIntakeControl.State.BLOCKED:AutomaticIntakeControl.State.BUSY;notice=flow.snapshot().saveState==Verification.SaveState.FAILED?"保存失败，请先重试保存；下一张卡未放行。":"当前核验或打印尚未结束，新卡不会排队。";break;
                case FAULT:state=AutomaticIntakeControl.State.BLOCKED;notice="读卡初始化、持久化或释放失败；已停止自动重开，请检查设备。";break;
                default:notice=currentRoster==null?"请先导入本场名单。":routeBlocked?"显示器已变更，请重新明确分配屏幕角色。":Adapters.mode()==Verification.Mode.LIVE&&app.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED?"请先授予相机权限，自动接卡尚未开始。":"自动接卡暂未就绪。";break;
            }}
            return new AutomaticIntakeControl.Model(state,test,state==AutomaticIntakeControl.State.SAME_CARD&&canChangePrintMode()&&!nameSaving.get()&&!modeSaving.get(),notice);
        }
        private ReceiptNameSetting receiptNames;
        private final AtomicBoolean nameSaving=new AtomicBoolean();
        private ReceiptNameControl.Status nameStatus=ReceiptNameControl.Status.LOADING;
        private volatile ReceiptNameControl.Model nameModel=new ReceiptNameControl.Model("",ReceiptNameControl.Status.LOADING,40,false,"正在读取小票名称设置。");
        private long nameRevision;
        private final AtomicBoolean modeSaving=new AtomicBoolean();
        private volatile PrintModeControl.Status modeStatus=PrintModeControl.Status.LOADING;
        private volatile PrintModeControl.Model modeModel=new PrintModeControl.Model(PrintModeControl.Mode.MANUAL,null,PrintModeControl.Status.LOADING,false,"正在读取已保存模式。");
        private PrintModeControl.Mode requestedMode;
        private long modeRevision;
        private FileRecordStore store;
        private FileRosterStore rosterStore;
        private RosterImportSession rosterImport;
        private RaceRoster currentRoster;
        private boolean rosterCorrupt;
        private volatile boolean cameraPermissionEligible;
        private RosterImportView.State rosterState=RosterImportView.State.IDLE;
        private String importEvent="",importGroup="",pickToken,rosterMessage="请选择本场Excel名单；全部校验通过后确认导入。";
        private int rosterErrorPage;
        private long rosterRevision;
        private String problem, publishedKey;
        private volatile Sink sink;
        private Page page = Page.MAIN;
        private boolean recordsLoading;
        private SettingsView.Model settingsModel;
        private final Context app;
        private DiagnosticsView.Model diagnosticsModel;
        private long diagnosticsRevision;
        private long recordsRevision;
        private RecordsView.Filter filter = RecordsView.Filter.ALL;
        private String selectedSessionId, recordsError;
        private List<Verification.Record> records = Collections.emptyList();

        Host(Context app) {
            this.app = app;
            worker.execute(() -> {
                try {
                    PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                    long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                    settingsModel = SettingsPresenter.model(Adapters.mode(), info.packageName, info.versionName, versionCode);
                    store = new FileRecordStore(new File(app.getFilesDir(), "prep-log"));
                    store.list();
                    rosterStore=new FileRosterStore(new File(app.getFilesDir(),"race-roster"));rosterImport=new RosterImportSession(rosterStore);reloadRoster();
                    runtime = Adapters.runtime(app,store,
                            new Verification.Clock() {
                                public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
                                public long wallMillis() { return System.currentTimeMillis(); }
                            },rosterStore);
                    flow = runtime.flow;
                    receiptNames=new ReceiptNameSetting(new ReceiptNamePreferences(app));
                    runtime.setReceiptHeading(receiptNames.override());
                    nameStatus=receiptNames.readFailed()?ReceiptNameControl.Status.FAILED:ReceiptNameControl.Status.READY;nameRevision++;
                    autoPrint=new AutoPrintPolicy(new PrintModePreferences(app));modeStatus=PrintModeControl.Status.READY;modeRevision++;
                    cameraPermissionEligible=Adapters.mode()==Verification.Mode.LIVE&&runtime.canBegin();
                    if(runtime.canBegin()&&runtime.inbox!=null&&runtime.cardSource!=null)intake=new AutoCardIntake(Adapters.mode(),runtime.cardSource,new PreferencesCardFence(app),new AutoCardIntake.Listener(){
                        public void admitted(AutoCardIntake.Admission admission){Host.this.admitted(admission);}
                        public void changed(AutoCardIntake.Snapshot value){intakeChanged();}
                    },15000);
                } catch (Exception | LinkageError ex) { problem = "配置、应用信息或日志初始化失败，核验操作未启用；请检查准备配置与私有存储。"; }
                publish();
            });
        }
        void bind(Sink current) {
            sink = current;
            worker.execute(() -> { publishedKey = null; if (flow != null) flow.tick(); publish(); });
        }
        void unbindAndCancel() {
            sink = null;
            if(intake!=null)intake.pause();
            worker.execute(() -> { if(runtime!=null&&runtime.inbox!=null)runtime.inbox.clear();if(autoPrint!=null)autoPrint.invalidate();if(runtime!=null)runtime.printing.cancel();if(page==Page.RECEIPT)page=Page.MAIN;if (flow != null) flow.cancel(); });
        }
        void refresh() {
            worker.execute(() -> { if (flow != null) flow.tick(); publish(); });
        }
        void refreshDisplay(){worker.execute(()->{publishedKey=null;publish();});}
        private boolean canChangePrintMode(){
            if(autoPrint==null||flow==null||runtime==null||sink==null)return false;
            Verification.Stage stage=flow.snapshot().stage;
            return (stage==Verification.Stage.IDLE||stage==Verification.Stage.FINISHED)&&runtime.printing.snapshot().status!=ReceiptPrintController.Status.SENDING;
        }
        void changeReceiptName(String draft){
            if(!nameSaving.compareAndSet(false,true))return;
            worker.execute(()->{try{
                if(receiptNames==null||page!=Page.SETTINGS||!canChangePrintMode()){nameStatus=ReceiptNameControl.Status.FAILED;return;}
                nameStatus=ReceiptNameControl.Status.SAVING;nameRevision++;publish();
                boolean saved=receiptNames.save(draft);
                if(saved)runtime.setReceiptHeading(receiptNames.override());
                nameStatus=saved?ReceiptNameControl.Status.READY:ReceiptNameControl.Status.FAILED;
            }finally{nameSaving.set(false);nameRevision++;publishedKey=null;publish();}});
        }
        private ReceiptNameControl.Model receiptName(){
            String notice=nameStatus==ReceiptNameControl.Status.FAILED?"名称未保存；请核对40字符上限、私有存储及当前核验/打印状态。":"只修改小票顶部赛事名称；留空沿用名单赛事名，原导入信息不变。";
            return new ReceiptNameControl.Model(receiptNames==null?"":receiptNames.override(),nameStatus,40,!nameSaving.get()&&!modeSaving.get()&&canChangePrintMode()&&nameStatus!=ReceiptNameControl.Status.LOADING,notice);
        }
        void changePrintMode(PrintModeControl.Mode requested){
            if(requested==null||!modeSaving.compareAndSet(false,true))return;
            worker.execute(()->{
                try{
                    if(!canChangePrintMode())return;
                    AutoPrintPolicy.Mode selected=requested==PrintModeControl.Mode.AUTO?AutoPrintPolicy.Mode.AUTOMATIC:AutoPrintPolicy.Mode.MANUAL;
                    if(autoPrint.mode()==selected)return;
                    requestedMode=requested;modeStatus=PrintModeControl.Status.SAVING;modeRevision++;publish();
                    boolean saved=autoPrint.setMode(selected);
                    requestedMode=null;modeStatus=saved?PrintModeControl.Status.READY:PrintModeControl.Status.FAILED;
                }finally{modeSaving.set(false);modeRevision++;publishedKey=null;publish();}
            });
        }
        private PrintModeControl.Model printMode(){
            PrintModeControl.Mode confirmed=autoPrint!=null&&autoPrint.mode()==AutoPrintPolicy.Mode.AUTOMATIC?PrintModeControl.Mode.AUTO:PrintModeControl.Mode.MANUAL;
            boolean enabled=!modeSaving.get()&&!nameSaving.get()&&canChangePrintMode()&&modeStatus!=PrintModeControl.Status.LOADING;
            String notice=modeStatus==PrintModeControl.Status.FAILED?"模式保存失败，仍按上次确认设置；请重试。":!enabled&&modeStatus==PrintModeControl.Status.READY?"请完成当前核验或打印后切换模式。":Adapters.mode()==Verification.Mode.TEST?"虚构测试：仅模拟打印，设置不会连接真实打印机。":"自动仅新核验通过并保存后提交一次；实际出纸待确认。";
            return new PrintModeControl.Model(confirmed,requestedMode,modeStatus,enabled,notice);
        }
        boolean mayRequestCamera(){return cameraPermissionEligible;}
        boolean mayAssignDisplays(){
            Verification f=flow;VerificationRuntime r=runtime;if(f==null||r==null||modeSaving.get()||nameSaving.get())return false;
            Verification.Stage stage=f.snapshot().stage;
            return (stage==Verification.Stage.IDLE||stage==Verification.Stage.FINISHED)&&r.printing.snapshot().status!=ReceiptPrintController.Status.SENDING;
        }
        void cameraPermissionDenied(){worker.execute(()->{problem="相机权限未授予，当前核验未开始；请核对设备权限。";publishedKey=null;publish();});}
        void openRecords() {
            worker.execute(() -> {
                if (page != Page.MAIN) return;
                if (!RecordsPresenter.canOpen(flow == null ? null : flow.snapshot())) return;
                page = Page.RECORDS; filter = RecordsView.Filter.ALL; selectedSessionId = null;
                recordsError = null; recordsLoading = true; recordsRevision++; publish();
                // Same serialized owner as verification saves; no new repository or fixture writes.
                try { records = store.list(); }
                catch (Exception ex) { records = Collections.emptyList(); recordsError = "读取失败。请返回后重新进入；现有日志未清理。"; }
                recordsLoading = false; recordsRevision++; publish();
            });
        }
        void openSettings() {
            worker.execute(() -> {
                if (page != Page.MAIN || settingsModel == null
                        || !SettingsPresenter.canOpen(flow == null ? null : flow.snapshot())) return;
                page = Page.SETTINGS; publishedKey = null; publish();
            });
        }
        void showMain() {
            worker.execute(() -> { page = Page.MAIN; publishedKey = null; publish(); });
        }
        void openReceipt(){worker.execute(()->{
            if(page!=Page.MAIN||runtime==null||flow==null)return;
            if(ReceiptPresenter.current(flow,flow.snapshot().sessionId)==null)return;
            page=Page.RECEIPT;publishedKey=null;publish();
        });}
        void printReceipt(String id,boolean retry){worker.execute(()->{
            if(page!=Page.RECEIPT||runtime==null||flow==null||!flow.snapshot().sessionId.equals(id))return;
            ReceiptPreviewView.Model model=ReceiptPresenter.model(flow,runtime.printing,runtime.printLayout,runtime.receiptHeading());
            if(retry?!model.retryEnabled:!model.printEnabled)return;
            CheckInReceipt.Document document=ReceiptPresenter.current(flow,id,runtime.receiptHeading());
            if(document==null||runtime.printLayout==null||autoPrint==null||!autoPrint.beforeManualPrint(flow))return;
            try{runtime.printing.submit(document,runtime.printLayout);}
            catch(IllegalStateException ex){/* A changed session is rejected by the controller; refresh from current state. */}
            publishedKey=null;publish();
        });}
        void cancelPrint(String id,String job){worker.execute(()->{
            if(page!=Page.RECEIPT||runtime==null||!flow.snapshot().sessionId.equals(id))return;
            if(runtime.printing.snapshot().jobId.equals(job))runtime.printing.cancel();
            publishedKey=null;publish();
        });}
        void leaveReceipt(String id){worker.execute(()->{
            if(page!=Page.RECEIPT||runtime==null||(id!=null&&!flow.snapshot().sessionId.equals(id)))return;
            runtime.printing.cancel();page=Page.MAIN;publishedKey=null;publish();
        });}
        private boolean canReplaceRoster(){Verification.Snapshot s=flow==null?null:flow.snapshot();return s!=null&&s.stage==Verification.Stage.IDLE&&s.saveState!=Verification.SaveState.FAILED;}
        private void reloadRoster(){try{currentRoster=rosterStore.current();rosterCorrupt=false;}catch(Exception ex){currentRoster=null;rosterCorrupt=true;}}
        void openRoster(){worker.execute(()->{if(page!=Page.MAIN||rosterImport==null||!SettingsPresenter.canOpen(flow==null?null:flow.snapshot()))return;page=Page.ROSTER;reloadRoster();rosterRevision++;publish();});}
        void beginChoice(String event,String group,Consumer<String> launch){worker.execute(()->{
            if(page!=Page.ROSTER||!canReplaceRoster()||rosterState==RosterImportView.State.READING||rosterState==RosterImportView.State.SAVING)return;
            if(rosterImport.candidate()!=null)rosterImport.discard(rosterImport.candidate().id);
            importEvent=event==null?"":event.trim();importGroup=group==null?"":group.trim();
            if(importEvent.length()>128||importGroup.length()>128){rosterState=RosterImportView.State.ERROR;rosterMessage="赛事名或默认组别过长，请缩短后重试。";rosterRevision++;publish();return;}
            pickToken=UUID.randomUUID().toString();rosterState=RosterImportView.State.READING;rosterMessage="请选择Excel文件。";rosterErrorPage=0;rosterRevision++;publish();launch.accept(pickToken);
        });}
        void cancelChoice(String token){worker.execute(()->{if(token==null||!token.equals(pickToken))return;pickToken=null;rosterState=RosterImportView.State.IDLE;rosterMessage="文件选择已取消，当前名单保持不变。";rosterRevision++;publish();});}
        void importFile(String token,Uri uri){worker.execute(()->{
            if(token==null||!token.equals(pickToken)||page!=Page.ROSTER||!canReplaceRoster())return;
            pickToken=null;rosterMessage="正在读取并校验Excel名单。";rosterRevision++;publish();
            String name="";
            try(Cursor c=app.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}
            try(InputStream input=app.getContentResolver().openInputStream(uri)){rosterImport.preview(input,name,importEvent,importGroup);}
            catch(Exception ex){rosterImport.preview(null,"unreadable.xls",importEvent,importGroup);}
            ExcelRosterReader.Preview candidate=rosterImport.candidate();rosterState=candidate.roster==null?RosterImportView.State.INVALID:RosterImportView.State.REVIEW;
            rosterMessage=candidate.roster==null?"名单校验未通过；没有替换当前有效名单。":"全部行校验通过，请确认替换本场名单。";rosterRevision++;publish();
        });}
        void confirmRoster(String id){worker.execute(()->{
            if(page!=Page.ROSTER||rosterImport==null)return;
            ExcelRosterReader.Preview candidate=rosterImport.candidate();if(candidate==null||!candidate.id.equals(id)||candidate.roster==null||!canReplaceRoster())return;
            rosterState=RosterImportView.State.SAVING;rosterMessage="正在保存本场名单。";rosterRevision++;publish();
            try{currentRoster=rosterImport.confirm(id,true);rosterCorrupt=false;rosterState=RosterImportView.State.SAVED;rosterMessage="名单已导入，可返回开始本场核验。";}
            catch(Exception ex){reloadRoster();rosterState=RosterImportView.State.REVIEW;rosterMessage="保存失败；请检查私有存储并重试，当前有效名单以已保存版本为准。";}
            rosterRevision++;publish();
        });}
        void discardRoster(String id){worker.execute(()->{if(page!=Page.ROSTER||rosterImport==null)return;ExcelRosterReader.Preview p=rosterImport.candidate();if(p==null||!p.id.equals(id))return;rosterImport.discard(id);rosterState=RosterImportView.State.IDLE;rosterMessage="候选已丢弃，当前有效名单保持不变。";rosterErrorPage=0;rosterRevision++;publish();});}
        void rosterErrorPage(String id,int pageNumber){worker.execute(()->{ExcelRosterReader.Preview p=rosterImport==null?null:rosterImport.candidate();if(page!=Page.ROSTER||p==null||!p.id.equals(id))return;rosterErrorPage=pageNumber;rosterRevision++;publish();});}
        void leaveRoster(){worker.execute(()->{if(page!=Page.ROSTER)return;pickToken=null;if(rosterImport!=null&&rosterImport.candidate()!=null)rosterImport.discard(rosterImport.candidate().id);rosterState=RosterImportView.State.IDLE;rosterErrorPage=0;page=Page.MAIN;rosterRevision++;publish();});}
        void openDiagnostics() {
            worker.execute(() -> {
                if (page != Page.SETTINGS || !SettingsPresenter.canOpen(flow == null ? null : flow.snapshot())) return;
                page = Page.DIAGNOSTICS; readDiagnostics();
            });
        }
        void refreshDiagnostics() {
            worker.execute(() -> { if (page == Page.DIAGNOSTICS) readDiagnostics(); });
        }
        private void readDiagnostics() {
            diagnosticsModel = DiagnosticsPresenter.loading(Adapters.mode());diagnosticsRevision++;publish();
            try { diagnosticsModel = DiagnosticsCollector.collect(app, Adapters.mode()); }
            catch (RuntimeException | LinkageError ex) { diagnosticsModel = DiagnosticsCollector.failed(Adapters.mode()); }
            diagnosticsRevision++;publish(); // All ordinary success/failure paths re-enable refresh.
        }
        void showSettings() {
            worker.execute(() -> { if (page != Page.DIAGNOSTICS) return;page = Page.SETTINGS;publishedKey = null;publish(); });
        }
        void filter(RecordsView.Filter current) {
            worker.execute(() -> {
                if (page != Page.RECORDS || recordsLoading || current == null) return;
                filter = current; selectedSessionId = null; recordsRevision++; publish();
            });
        }
        void select(String id) {
            worker.execute(() -> {
                if (page != Page.RECORDS || recordsLoading) return;
                selectedSessionId = id; recordsRevision++; publish();
            });
        }
        void act(VerificationView.PrimaryAction action, boolean cancel) {
            worker.execute(() -> {
                if (flow == null || page != Page.MAIN || sink==null) return;
                try {
                    problem = null;
                    if (cancel) {if(intake!=null)intake.pause();if(autoPrint!=null)autoPrint.invalidate();runtime.printing.cancel();flow.cancel();}
                    else switch (action) {
                        case BEGIN: case RETAKE:
                            if(runtime!=null&&runtime.cardSource instanceof TestCardSource&&waitingForCard()&&!modeSaving.get()&&!nameSaving.get()){
                                boolean second=(syntheticIndex%2)==1;
                                if(((TestCardSource)runtime.cardSource).emit(second?"合成证件乙":"合成证件甲",second?"990101200001010022":"990101200001010014",new byte[]{1}))syntheticIndex++;
                            }
                            break;
                        case NEXT: if(autoPrint!=null)autoPrint.invalidate();runtime.printing.cancel();flow.next(); break;
                        case RETRY_SAVE: flow.retrySave(); break;
                        default: break;
                    }
                } catch (IllegalStateException ex) {
                    problem = "本轮记录尚未保存，请重试保存后再开始下一位。";
                } catch (Exception ex) { problem = "操作未完成，请检查本轮状态和私有存储。"; }
                publish();
            });
        }
        private void publish() {
            if(sink!=null&&autoPrint!=null&&runtime!=null&&flow!=null&&!modeSaving.get()&&!nameSaving.get())autoPrint.tryAutomaticPrint(flow,runtime.printing,runtime.printLayout);
            syncIntake();
            // All policy locks/preferences stay on the worker; the UI receives only immutable snapshots.
            modeModel=printMode();
            nameModel=receiptName();
            Verification.Snapshot state = flow == null ? null : flow.snapshot();
            ReceiptPrintController.Snapshot print=runtime==null?null:runtime.printing.snapshot();
            String key = (state == null ? "initializing" : state.sessionId + "/" + state.stage + "/" + state.saveState)
                    + "/" + problem + "/" + page + "/" + recordsRevision + "/" + diagnosticsRevision+"/"+rosterRevision+"/"+modeRevision+"/"+nameRevision+"/"+(print==null?"":print.jobId+"/"+print.status+"/"+print.failure);
            if (key.equals(publishedKey)) return;
            publishedKey = key;
            RecordsView.Model recordsModel = page == Page.RECORDS ? RecordsPresenter.model(records,
                    Adapters.mode(), filter, selectedSessionId, recordsLoading, recordsError) : null;
            Page currentPage = page;
            DiagnosticsView.Model currentDiagnostics = diagnosticsModel;
            boolean canOpen = SettingsPresenter.canOpen(state);
            RosterImportView.Model rosterModel=page==Page.ROSTER?RosterImportPresenter.model(Adapters.mode()==Verification.Mode.TEST,rosterState,currentRoster,rosterCorrupt,
                    rosterImport==null?null:rosterImport.candidate(),importEvent,importGroup,rosterErrorPage,
                    canReplaceRoster()?rosterMessage:"请先返回完成本轮并进入下一位，再替换名单。",canReplaceRoster()):null;
            AutomaticIntakeControl.Model inputModel=intakeModel();
            Sink expected=sink;
            main.post(() -> {
                if(sink==null || sink!=expected||currentPage!=page)return;
                // Re-read the synchronized core here: an older queued IDLE render must not erase newly accepted images.
                Verification.Snapshot latest=flow==null?null:flow.snapshot();
                VerificationView.Model latestModel=VerificationPresenter.model(latest,Adapters.mode(),problem,runtime!=null && runtime.canBegin()&&!modeSaving.get()&&!nameSaving.get());
                ReceiptPreviewView.Model receipt=flow==null||runtime==null?null:ReceiptPresenter.model(flow,runtime.printing,runtime.printLayout,runtime.receiptHeading());
                if(intake!=null)latestModel=automaticModel(latestModel,latest);
                sink.show(latestModel,recordsModel,settingsModel,currentDiagnostics,currentPage,canOpen,latest,runtime==null?null:runtime.display,rosterModel,receipt,modeModel,nameModel,inputModel);
            });
        }
        private VerificationView.Model automaticModel(VerificationView.Model old,Verification.Snapshot state){
            VerificationView.PrimaryAction action=state!=null&&state.saveState==Verification.SaveState.FAILED?VerificationView.PrimaryAction.RETRY_SAVE:
                Adapters.mode()==Verification.Mode.TEST&&waitingForCard()?VerificationView.PrimaryAction.BEGIN:VerificationView.PrimaryAction.NONE;
            boolean standby=state!=null&&state.stage==Verification.Stage.IDLE;
            return new VerificationView.Model(old.simulation,old.step,standby?"自动待机接卡":old.title,
                standby?"等待当前虚构测试卡或已授权读卡器输入；无卡不创建记录。":old.message,
                standby?"刷卡后自动核验":old.instruction,old.name,old.maskedNumber,old.score,old.liveness,old.saveStatus,
                old.certificatePhoto,old.livePhoto,action,action!=VerificationView.PrimaryAction.NONE,old.cancelEnabled,old.tone,old.rosterInfo);
        }
        void close() {
            shuttingDown=true;if(intake!=null)intake.close();
            cameraPermissionEligible=false;
            sink = null;
            worker.execute(() -> { if (runtime != null) runtime.close();else if (flow != null) flow.cancel(); });
            worker.shutdown();
        }
    }
}
