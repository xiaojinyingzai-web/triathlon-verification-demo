package com.triathlon.prep;
import com.triathlon.prep.core.CheckIn;
import com.triathlon.prep.core.PrintActionPermit;
import com.triathlon.prep.core.nextday.VerificationModeSession;
import com.triathlon.prep.nextday.host.NextDayScreenSession;
import com.triathlon.prep.ui.VerificationModeControl;
import com.triathlon.prep.ui.NextDayFaceView;
import com.triathlon.prep.ui.FaceLibraryManagementView;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.Objects;
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
import com.triathlon.prep.core.DeviceConfigDraft;
import com.triathlon.prep.core.DeviceConfigSession;
import com.triathlon.prep.core.FileDeviceConfigStore;
import com.triathlon.prep.ui.RecordsView;
import com.triathlon.prep.ui.DiagnosticsView;
import com.triathlon.prep.ui.SettingsView;
import com.triathlon.prep.ui.DeviceConfigView;
import com.triathlon.prep.ui.VerificationView;
import com.triathlon.prep.ui.RosterImportView;
import com.triathlon.prep.ui.ReceiptPreviewView;
import com.triathlon.prep.ui.ReceiptTemplateSettingsView;
import com.triathlon.prep.ui.ReceiptBitmapRenderer;
import com.triathlon.prep.core.ReceiptTemplate;
import com.triathlon.prep.core.FileReceiptTemplateStore;
import com.triathlon.prep.core.ManualPassportCheck;
import com.triathlon.prep.core.FileManualRecordStore;
import com.triathlon.prep.core.ManualPrintAuthorization;
import com.triathlon.prep.ui.PassportCheckView;
import com.triathlon.prep.core.CheckInReceipt;
import com.triathlon.prep.core.ReceiptPrintController;
import com.triathlon.prep.core.AutoPrintPolicy;
import com.triathlon.prep.ui.PrintModeControl;
import com.triathlon.prep.ui.ReceiptNameControl;
import com.triathlon.prep.ui.DisplayRoleControl;
import com.triathlon.prep.ui.AthleteView;
import com.triathlon.prep.ui.AutomaticIntakeControl;
import com.triathlon.prep.ui.SpeechControl;
import com.triathlon.prep.speech.*;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.UnaryOperator;
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
    private VerificationModeControl operationModeControl;
    private NextDayFaceView faceScreen;
    private FaceLibraryManagementView libraryScreen;
    private android.app.AlertDialog libraryKeyDialog;
    private long libraryKeyDialogEpoch;
    private String libraryKeyDialogRevision="";
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
    private DeviceConfigView deviceConfigScreen;
    private DiagnosticsView diagnosticsScreen;
    private RosterImportView rosterScreen;
    private ReceiptPreviewView receiptScreen;
    private ReceiptTemplateSettingsView templateScreen;
    private PassportCheckView passportScreen;
    private Button recordsEntry, settingsEntry,rosterEntry,receiptEntry;
    private LinearLayout navigation;
    private enum Page { MAIN, RECORDS, SETTINGS, DIAGNOSTICS, ROSTER, RECEIPT, TEMPLATE, PASSPORT, DEVICE_CONFIG, LIBRARY }
    private static final int PICK_ROSTER=1801;
    private static final int SAVE_ROSTER_EXPORT=1802;
    private static final int CAMERA_PERMISSION=2001;
    private static final int PICK_FACE_LIBRARY=3901,SAVE_FACE_LIBRARY=3902;
    private String faceImportPickerToken,faceExportPickerToken;
    private VerificationView.PrimaryAction pendingCameraAction;
    private String pickerToken;
    private String exportPickerToken;
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
        if(state!=null)exportPickerToken=state.getString("rosterExportToken");
        if(state!=null){faceImportPickerToken=state.getString("faceImportPickerToken");faceExportPickerToken=state.getString("faceExportPickerToken");}
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
        },new PrintModeControl.Actions(){
            public void onModeChangeRequested(PrintModeControl.Mode requested){if(visible)host.changePrintMode(requested);}
            public void onEnabledChangeRequested(boolean enabled){if(visible)host.changePrintEnabled(enabled);}
        },()->{if(visible)host.recheckCard();});
        recordsScreen = new RecordsView(uiContext, new RecordsView.Actions() {
            public void onBack() { if (visible) host.showMain(); }
            public void onFilterChanged(RecordsView.Filter filter) { if (visible) host.filter(filter); }
            public void onRecordSelected(String id) { if (visible) host.select(id); }
            public void onCloseDetail() { if (visible) host.select(null); }
        });
        recordsScreen.setOnPageRequested(index->{if(visible)host.recordsPage(index);});
        operationModeControl=new VerificationModeControl(uiContext,(mode,token,revision)->{if(visible){host.requestOperationMode(mode,token,revision);operationModeControl.render(host.operationModeModel());}});
        faceScreen=new NextDayFaceView(uiContext,new NextDayFaceView.Actions(){
            public void onPrimaryAction(String id,String token,long revision){if(visible)host.facePrimary(id,token,revision);}
            public void onCancel(String id,String token,long revision){if(visible)host.faceCancel(id,token,revision);}
            public void onNextPerson(String id,String token,long revision){if(visible)host.faceNext(id,token,revision);}
            public void onManualReview(String id,String token,long revision){if(visible)host.faceManual(id,token,revision);}
            public void onBack(String id,String token,long revision){if(visible)host.faceBack(id,token,revision);}
            public void onSurfaceAvailable(String token,long revision,android.view.Surface surface,int width,int height){if(visible)host.faceSurfaceAvailable(token,revision,surface,width,height);}
            public void onSurfaceDestroyed(String token,long revision){host.faceSurfaceDestroyed(token,revision);}
            public void onSurfaceFrameUpdated(String token,long revision,long timestamp){if(visible)host.faceSurfaceFrame(token,revision,timestamp);}
        });
        settingsScreen = new SettingsView(uiContext, new SettingsView.Actions(){
                public void onBack(){if(visible)host.showMain();}
                public void onOpenNextDayFace(){if(visible)host.openFaceEntry();}
                public void onOpenFaceLibrary(){if(visible)host.openLibrary();}
            },
                () -> { if (visible) host.openDiagnostics(); },draft->{if(visible)host.changeReceiptName(draft);},this::assignDisplays,new SpeechControl.Actions(){
                    public void onEnabledRequested(boolean enabled){if(visible)host.changeSpeechEnabled(enabled);}
                    public void onVolumeRequested(int volume){if(visible)host.changeSpeechVolume(volume);}
                    public void onTextsSaveRequested(Map<SpeechSettings.Scene,String> drafts){if(visible)host.changeSpeechTexts(drafts);}
                    public void onPreviewRequested(SpeechSettings.Scene scene,String draft,int volume){if(visible)host.previewSpeech(scene,draft,volume);}
                });
        diagnosticsScreen = new DiagnosticsView(uiContext, new DiagnosticsView.Actions() {
            public void onBack() { if (visible) host.showSettings(); }
            public void onRefresh() { if (visible) host.refreshDiagnostics(); }
        });
        rosterScreen=new RosterImportView(uiContext,new RosterImportView.Actions(){
            public void onExportRequested(){if(visible)host.beginRosterExport((token,name)->main.post(()->{
                if(!visible||isDestroyed()){host.cancelRosterExport(token);return;}
                exportPickerToken=token;Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.addCategory(Intent.CATEGORY_OPENABLE);
                save.setType("application/vnd.ms-excel");save.putExtra(Intent.EXTRA_TITLE,name);
                try{startActivityForResult(save,SAVE_ROSTER_EXPORT);}catch(RuntimeException ex){exportPickerToken=null;host.failRosterExportChoice(token);}
            }));}
            public void onChooseFile(String event,String group){if(visible)chooseRosterFileRange(event,group);}
            public void onConfirmImport(String id){if(visible)host.confirmRoster(id);}
            public void onDiscardPreview(String id){if(visible)host.discardRoster(id);}
            public void onErrorPageChanged(String id,int page){if(visible)host.rosterErrorPage(id,page);}
            public void onBack(){if(visible)host.leaveRoster();}
        });
        receiptScreen=new ReceiptPreviewView(uiContext,new ReceiptPreviewView.Actions(){
            public void onPrintRequested(String id,String token,long revision,boolean confirmed){if(visible)host.printReceipt(id,false,token,revision,confirmed);}
            public void onRetryRequested(String id,String token,long revision,boolean confirmed){if(visible)host.printReceipt(id,true,token,revision,confirmed);}
            public void onCancelRequested(String id,String job){if(visible)host.cancelPrint(id,job);}
            public void onBack(String id){if(visible)host.leaveReceipt(id);}
        });
        templateScreen=new ReceiptTemplateSettingsView(uiContext,new ReceiptTemplateSettingsView.Actions(){
            public void onSave(ReceiptTemplate draft){if(visible)host.saveReceiptTemplate(draft);}
            public void onCancel(){if(visible)host.leaveReceiptTemplate();}
            public void onBack(){if(visible)host.leaveReceiptTemplate();}
        });
        settingsScreen.setOnReceiptTemplateRequested(()->{if(visible)host.openReceiptTemplate();});
        deviceConfigScreen=new DeviceConfigView(uiContext,new DeviceConfigView.Actions(){
            public void onDraftChanged(DeviceConfigDraft draft){if(visible)host.changeConfigDraft(draft);}
            public void onSaveRequested(String token,long revision){if(visible)host.saveConfig(token,revision);}
            public void onRollbackRequested(long revision){if(visible)host.rollbackConfig(revision);}
            public void onBack(){if(visible)host.leaveDeviceConfig();}
        });
        settingsScreen.setOnDeviceConfigRequested(()->{if(visible)host.openDeviceConfig();});
        passportScreen=new PassportCheckView(uiContext,new PassportCheckView.Actions(){
            public void onQueryChanged(String query){if(visible)host.searchPassport(query);}
            public void onPageRequested(String token,int page){if(visible)host.passportPage(token,page);}
            public void onCandidateSelected(String token){if(visible)host.selectPassport(token);}
            public void onConfirmAndPrint(String token){if(visible)host.confirmPassport(token);}
            public void onReprint(String selection,String token,long revision,boolean confirmed){if(visible)host.reprintPassport(selection,token,revision,confirmed);}
            public void onBack(){if(visible)host.leavePassport();}
        });
        passportScreen.setSimulation(Adapters.mode()==Verification.Mode.TEST);
        settingsScreen.setOnPassportCheckRequested(()->{if(visible)host.openPassport();});
        screen.render(VerificationPresenter.model(null, Adapters.mode(), null));
        LinearLayout root = new LinearLayout(uiContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff5f7fa);
        root.addView(operationModeControl,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout pages = new FrameLayout(uiContext);
        pages.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(faceScreen,new FrameLayout.LayoutParams(-1,-1));
        faceScreen.setVisibility(View.GONE);
        pages.addView(recordsScreen, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(settingsScreen, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(deviceConfigScreen,new FrameLayout.LayoutParams(-1,-1));
        pages.addView(diagnosticsScreen, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(rosterScreen,new FrameLayout.LayoutParams(-1,-1));
        pages.addView(receiptScreen,new FrameLayout.LayoutParams(-1,-1));
        pages.addView(templateScreen,new FrameLayout.LayoutParams(-1,-1));
        pages.addView(passportScreen,new FrameLayout.LayoutParams(-1,-1));
        recordsScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        deviceConfigScreen.setVisibility(View.GONE);
        diagnosticsScreen.setVisibility(View.GONE);
        rosterScreen.setVisibility(View.GONE);
        receiptScreen.setVisibility(View.GONE);
        templateScreen.setVisibility(View.GONE);
        passportScreen.setVisibility(View.GONE);
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
        libraryScreen=new FaceLibraryManagementView(uiContext,new FaceLibraryManagementView.Actions(){
            public void onRefresh(String id,String token,long rev){if(visible)host.libraryRefresh(id,token,rev);}
            public void onExport(String id,String token,long rev){if(visible)host.libraryExport(id,token,rev,MainActivity.this::launchFaceExport);}
            public void onChooseImport(String id,String token,long rev){if(visible)host.libraryChooseImport(id,token,rev,MainActivity.this::showImportKey);}
            public void onConfirmImport(String id,String preflight,String token,long rev,boolean confirmed){if(visible)host.libraryConfirm(id,preflight,token,rev,confirmed);}
            public void onCancelPreflight(String id,String preflight,String token,long rev){if(visible)host.libraryDiscard(id,preflight,token,rev);}
            public void onClearLibrary(String id,String token,long rev,boolean confirmed){if(visible)host.libraryClear(id,token,rev,confirmed);}
            public void onRestorePreviousLibrary(String id,String token,long rev,boolean confirmed){if(visible)host.libraryRestore(id,token,rev,confirmed);}
            public void onPageRequested(int page,String id,String token,long rev){if(visible)host.libraryPage(page,id,token,rev);}
            public void onBack(String id,String token,long rev){if(visible)host.libraryBack(id,token,rev);}
        });libraryScreen.setVisibility(View.GONE);pages.addView(libraryScreen,new FrameLayout.LayoutParams(-1,-1));
        staffRoot=root;staffAvailable=true;
        root.requestApplyInsets();
        return root;
    }

    private void dismissLibraryKey(){android.app.AlertDialog old=libraryKeyDialog;libraryKeyDialog=null;if(old!=null)old.dismiss();}
    private void markLibraryDialog(){libraryKeyDialogEpoch=host.operationModes.snapshot().epoch;FaceLibraryManagementView.Model model=host.libraryPublished;libraryKeyDialogRevision=model==null?"":model.libraryRevision;}
    private void showImportKey(){
        if(!visible||showingPage!=Page.LIBRARY){host.cancelLibraryChoice();return;}dismissLibraryKey();
        android.widget.EditText input=new android.widget.EditText(this);input.setSingleLine(true);input.setHint("NDK1: 独立传递密钥");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setSaveEnabled(false);input.setSaveFromParentEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        java.util.concurrent.atomic.AtomicBoolean submitted=new java.util.concurrent.atomic.AtomicBoolean();
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("导入库包的独立密钥").setMessage("请输入发送方单独提供的传递密钥。它不是设备密钥或百度授权码，输入不保存。").setView(input).setPositiveButton("继续选择加密文件",(d,w)->{String code=input.getText().toString();input.getText().clear();submitted.set(true);host.libraryImportKey(code,this::launchFaceImport);}).setNegativeButton("取消",(d,w)->{}).create();
        libraryKeyDialog=dialog;markLibraryDialog();dialog.setOnDismissListener(d->{input.getText().clear();if(libraryKeyDialog==dialog)libraryKeyDialog=null;if(!submitted.get())host.cancelLibraryChoice();});dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void showExportKey(String key){
        if(key==null||key.isEmpty()||!visible||showingPage!=Page.LIBRARY)return;dismissLibraryKey();
        android.widget.TextView value=new android.widget.TextView(this);value.setText(key);value.setTextIsSelectable(true);value.setPadding(24,16,24,16);value.setSaveEnabled(false);value.setSaveFromParentEnabled(false);value.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("本次库包传递密钥").setMessage("请单独将密钥交给接收设备，不随库文件保存。关闭后不再保留显示内容。").setView(value).setPositiveButton("已保存密钥，关闭",(d,w)->{}).create();libraryKeyDialog=dialog;markLibraryDialog();dialog.setOnDismissListener(d->{value.setText("");if(libraryKeyDialog==dialog)libraryKeyDialog=null;});dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void launchFaceExport(String token){if(!visible||showingPage!=Page.LIBRARY){host.cancelLibraryChoice();return;}faceExportPickerToken=token;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.setType("application/octet-stream");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_TITLE,"nextday-face-library.ndf");try{startActivityForResult(intent,SAVE_FACE_LIBRARY);}catch(RuntimeException unavailable){faceExportPickerToken=null;host.cancelLibraryChoice();}}
    private void launchFaceImport(String token){if(!visible||showingPage!=Page.LIBRARY){host.cancelLibraryChoice();return;}faceImportPickerToken=token;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("application/octet-stream");intent.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(intent,PICK_FACE_LIBRARY);}catch(RuntimeException unavailable){faceImportPickerToken=null;host.cancelLibraryChoice();}}
    @Override public void onStart() {
        super.onStart(); visible = true;
        displayRoutes.start();syncWindows();bindHost();host.maybeShowExportKey(MainActivity.this::showExportKey);
        main.post(ticker);
    }
    private void bindHost(){
        host.beforeAdmission=()->{clearDisplay();if(activeDisplay!=null)activeDisplay.arm();};
        host.bind((model, records, settings, diagnostics, page, canOpen, state, display,roster,receipt,printMode,receiptName,intakeModel,speechModel,recordPage,configModel,operationMode,faceModel,libraryModel) -> {
            if (!visible || isDestroyed()) return;
            boolean checkin=operationMode.state==VerificationModeControl.SwitchState.STABLE&&operationMode.currentMode==VerificationModeControl.OperationMode.CHECKIN;
            renderAthlete(checkin?state:null);renderRoles();
            if(!staffAvailable)return;
            if(activeDisplay!=display || page!=Page.MAIN||!checkin){clearDisplay();if(activeDisplay!=null)activeDisplay.detach();}
            activeDisplay=page==Page.MAIN&&checkin?display:null;
            showingPage = page;
            operationModeControl.render(operationMode);
            screen.setVisibility(page == Page.MAIN&&operationMode.currentMode==VerificationModeControl.OperationMode.CHECKIN ? View.VISIBLE : View.GONE);
            boolean showFace=page==Page.MAIN&&operationMode.currentMode==VerificationModeControl.OperationMode.FACE;
            faceScreen.setVisibility(showFace?View.VISIBLE:View.GONE);
            if(showFace)faceScreen.render(faceModel);else faceScreen.unbind();
            libraryScreen.setVisibility(page==Page.LIBRARY?View.VISIBLE:View.GONE);
            if(page==Page.LIBRARY&&libraryModel!=null)libraryScreen.render(libraryModel);else libraryScreen.unbind();
            if(libraryKeyDialog!=null&&(page!=Page.LIBRARY||host.operationModes.snapshot().epoch!=libraryKeyDialogEpoch||libraryModel==null||!libraryModel.libraryRevision.equals(libraryKeyDialogRevision)))dismissLibraryKey();
            if(!checkin){screen.bindSession(null);if(operationMode.currentMode==VerificationModeControl.OperationMode.CHECKIN)screen.render(VerificationPresenter.model(null,Adapters.mode(),"模式交接未完成，请等待资源释放。",false));}
            recordsScreen.setVisibility(page == Page.RECORDS ? View.VISIBLE : View.GONE);
            settingsScreen.setVisibility(page == Page.SETTINGS ? View.VISIBLE : View.GONE);
            deviceConfigScreen.setVisibility(page==Page.DEVICE_CONFIG?View.VISIBLE:View.GONE);
            diagnosticsScreen.setVisibility(page == Page.DIAGNOSTICS ? View.VISIBLE : View.GONE);
            rosterScreen.setVisibility(page==Page.ROSTER?View.VISIBLE:View.GONE);
            receiptScreen.setVisibility(page==Page.RECEIPT?View.VISIBLE:View.GONE);
            templateScreen.setVisibility(page==Page.TEMPLATE?View.VISIBLE:View.GONE);
            passportScreen.setVisibility(page==Page.PASSPORT?View.VISIBLE:View.GONE);
            if(page!=Page.RECEIPT)receiptScreen.bindSession(null);
            navigation.setVisibility(page == Page.MAIN ? View.VISIBLE : View.GONE);
            recordsEntry.setEnabled(canOpen);
            settingsEntry.setEnabled(canOpen);
            rosterEntry.setEnabled(canOpen);
            receiptEntry.setEnabled(checkin&&receipt!=null&&receipt.document!=null);
            if (page == Page.RECORDS) {recordsScreen.render(records);recordsScreen.renderPage(recordPage.page,recordPage.pageCount,recordPage.total);}
            else if(page==Page.DEVICE_CONFIG)deviceConfigScreen.render(configModel);
            else if (page == Page.SETTINGS) {settingsScreen.render(settings);settingsScreen.renderReceiptName(receiptName);settingsScreen.renderDisplayRoles(roleModel());settingsScreen.renderSpeech(speechModel);}
            else if (page == Page.DIAGNOSTICS) diagnosticsScreen.render(diagnostics);
            else if(page==Page.ROSTER){rosterScreen.render(roster);rosterScreen.renderExport(host.rosterExportModel);}
            else if(page==Page.TEMPLATE){templateScreen.render(host.templateModel);}
            else if(page==Page.PASSPORT&&host.passportModel!=null){passportScreen.render(host.passportModel);}
            else if(page==Page.RECEIPT&&receipt!=null){receiptScreen.bindSession(receipt.sessionId);receiptScreen.render(receipt);}
            else if(checkin) {
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
        if(showingPage==Page.LIBRARY){FaceLibraryManagementView.Model model=host.libraryPublished;if(model!=null)host.libraryBack(model.operationId,model.requestToken,model.revision);}
        else if(showingPage==Page.PASSPORT)host.leavePassport();
        else if(showingPage==Page.TEMPLATE)host.leaveReceiptTemplate();
        else if(showingPage==Page.RECEIPT)host.leaveReceipt(null);
        else if(showingPage==Page.ROSTER)host.leaveRoster();
        else if(showingPage==Page.DEVICE_CONFIG)host.leaveDeviceConfig();
        else if (showingPage == Page.DIAGNOSTICS) host.showSettings();
        else if (showingPage != Page.MAIN) host.showMain();
        else super.onBackPressed();
    }
    @Override public void onStop() {
        dismissLibraryKey();
        pendingCameraAction=null;
        if(displayRoutes.roles.staff()!=null&&!host.chooserPauseValid())host.routeBlocked=true;
        windows.close();displayRoutes.close();
        visible = false; main.removeCallbacks(ticker);clearDisplay();
        if(receiptScreen!=null)receiptScreen.bindSession(null);
        if(athleteScreen!=null)athleteScreen.bindSession(null);
        if(activeDisplay!=null){activeDisplay.detach();activeDisplay=null;}
        host.unbindAndCancel();
        super.onStop();
    }
    @Override public Object onRetainNonConfigurationInstance() { return host; }
    private AlertDialog rosterScopeDialog;
    private void chooseRosterFileRange(String event,String group){
        if(!visible||isDestroyed()||rosterScopeDialog!=null)return;
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("名单导入范围")
            .setItems(new String[]{"全部工作表","仅第一个工作表"},(d,which)->launchRosterPicker(event,group,which==0?ExcelRosterReader.WorksheetScope.ALL_WORKSHEETS:ExcelRosterReader.WorksheetScope.FIRST_WORKSHEET)).create();
        rosterScopeDialog=dialog;dialog.setOnDismissListener(d->{if(rosterScopeDialog==d)rosterScopeDialog=null;});dialog.show();
    }
    private void launchRosterPicker(String event,String group,ExcelRosterReader.WorksheetScope scope){
        if(!visible||isDestroyed())return;
        host.beginChoice(event,group,scope,token->main.post(()->{
            if(!visible||isDestroyed()){host.cancelChoice(token);return;}
            pickerToken=token;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"});
            try{startActivityForResult(pick,PICK_ROSTER);}catch(RuntimeException ex){pickerToken=null;host.cancelChoice(token);}
        }));
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("rosterPickerToken",pickerToken);state.putString("rosterExportToken",exportPickerToken);state.putString("faceImportPickerToken",faceImportPickerToken);state.putString("faceExportPickerToken",faceExportPickerToken);state.putBoolean("neutralActivity",host.neutralActivity);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_FACE_LIBRARY||request==SAVE_FACE_LIBRARY){boolean exporting=request==SAVE_FACE_LIBRARY;String token=exporting?faceExportPickerToken:faceImportPickerToken;if(exporting)faceExportPickerToken=null;else faceImportPickerToken=null;if(token!=null)host.libraryFileSelected(token,result==RESULT_OK&&data!=null?data.getData():null,exporting,MainActivity.this::showExportKey);return;}
        if(request==SAVE_ROSTER_EXPORT){String token=exportPickerToken;exportPickerToken=null;if(token==null)return;
            if(result==RESULT_OK&&data!=null&&data.getData()!=null)host.writeRosterExport(token,data.getData());else host.cancelRosterExport(token);return;}
        if(request!=PICK_ROSTER)return;
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
        if(rosterScopeDialog!=null){rosterScopeDialog.dismiss();rosterScopeDialog=null;}
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
        View previousStaffRoot=staffRoot;
        boolean previouslyAvailable=staffAvailable;
        displayRoutes.refresh();
        String next=displayRoutes.roles.staff()+"/"+displayRoutes.roles.athlete();
        if(!routeKey.equals(next)){if(!routeKey.isEmpty()&&!routeKey.equals("null/null")&&next.equals("null/null"))host.routeBlocked=true;clearDisplay();routeKey=next;host.refreshDisplay();}
        if(!windows.sync(displayRoutes.roles))roleStatus=DisplayRoleControl.Status.FAILED;
        staffAvailable=staffRoot!=null&&staffRoot.isAttachedToWindow();
        // A skipped snapshot still occupies Host's dedup key. A new/just-attached staff window
        // must receive current models even when the business state itself has not changed.
        if(staffAvailable&&(!previouslyAvailable||previousStaffRoot!=staffRoot))host.refreshDisplay();
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
                Verification.Snapshot state,VerificationDisplay display,RosterImportView.Model roster,ReceiptPreviewView.Model receipt,PrintModeControl.Model printMode,ReceiptNameControl.Model receiptName,AutomaticIntakeControl.Model intakeModel,SpeechControl.Model speechModel,RecordPageSnapshot recordPage,DeviceConfigView.Model configModel,VerificationModeControl.Model operationMode,NextDayFaceView.Model faceModel,FaceLibraryManagementView.Model libraryModel);
                
    }
    private static final class RecordPageSnapshot {
        final int page,pageCount,total;RecordPageSnapshot(int page,int pageCount,int total){this.page=page;this.pageCount=pageCount;this.total=total;}
    }

    /** One store/worker/core across pages and configuration recreation. */
    private static final class Host {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private Verification flow;
        private VerificationRuntime runtime;
        private volatile AutoPrintPolicy autoPrint;
        private AutoCardIntake intake;
        private volatile Runnable beforeAdmission;
        // Revoke authority on the caller thread; only resource disposal waits for the worker.
        private final VerificationModeSession operationModes=new VerificationModeSession((old,target,epoch,reply)->{
            try{worker.execute(()->releaseForMode(old,target,epoch,reply));}
            catch(java.util.concurrent.RejectedExecutionException closed){reply.released(false);}
        });
        private final com.triathlon.prep.core.nextday.QualifiedSceneSlot sceneSlot=new com.triathlon.prep.core.nextday.QualifiedSceneSlot(
            Adapters.mode(),operationModes,new Verification.Clock(){public long elapsedMillis(){return SystemClock.elapsedRealtime();}public long wallMillis(){return System.currentTimeMillis();}},15000);
        private void clearScene(){if(sceneSlot!=null)sceneSlot.clear();}
        private com.triathlon.prep.core.nextday.FaceEnrollment checkInEnrollment;
        private final NextDaySurfaceTarget faceSurface=new NextDaySurfaceTarget();
        private com.triathlon.prep.nextday.host.NextDayModule nextDayModule;
        private com.triathlon.prep.core.nextday.FaceLibrarySelection faceSelection;
        private String selectionRosterBatch="";
        private boolean bankSwitching,bankBlocked;
        private volatile com.triathlon.prep.nextday.host.LibraryHostSession librarySession;
        private volatile FaceLibraryManagementView.Model libraryPublished;
        private volatile Consumer<String> libraryKeyPresenter;
        private String emptyLibraryId=UUID.randomUUID().toString(),emptyLibraryToken=UUID.randomUUID().toString();
        private volatile boolean libraryChooserPending;
        private volatile String libraryChooserId="";
        private volatile long libraryChooserDeadline,libraryChooserEpoch;
        private VerificationModeSession.Mode libraryChooserMode;
        private com.triathlon.prep.nextday.host.LibraryHostSession libraryChooserOwner;
        private boolean nextDayCameraConfigured,nextDayReleaseConfirmed=true;
        private final java.util.concurrent.atomic.AtomicBoolean nextDayQueued=new java.util.concurrent.atomic.AtomicBoolean();
        private final com.triathlon.prep.core.nextday.CheckInEnrollmentCoordinator enrollmentHook=new com.triathlon.prep.core.nextday.CheckInEnrollmentCoordinator(sceneSlot,this::enrollmentForSaved);
        private void clearFaceSurface(){if(faceSurface!=null)faceSurface.clear();}
        private boolean canLibrary(){return !shuttingDown&&sink!=null&&page==Page.LIBRARY&&!routeBlocked;}
        private void libraryChanged(){if(bankSwitching)return;publishedKey=null;publish();if(librarySession!=null&&librarySession.keyReady()&&libraryKeyPresenter!=null)maybeShowExportKey(libraryKeyPresenter);}
        private void dispatchLibrary(Runnable work){if(!shuttingDown)try{worker.execute(work);}catch(java.util.concurrent.RejectedExecutionException ignored){}}
        private void cancelLibraryWork(){libraryChooserPending=false;if(librarySession!=null)librarySession.close();}
        private boolean chooserPauseValid(){return libraryChooserPending&&page==Page.LIBRARY&&libraryChooserOwner!=null&&!libraryChooserOwner.closed()&&SystemClock.elapsedRealtime()<=libraryChooserDeadline&&operationModes.allows(libraryChooserMode,libraryChooserEpoch);}
        private void rememberChooser(com.triathlon.prep.nextday.host.LibraryHostSession owner,String id){VerificationModeSession.Snapshot m=operationModes.snapshot();libraryChooserOwner=owner;libraryChooserId=id;libraryChooserEpoch=m.epoch;libraryChooserMode=m.currentMode;libraryChooserDeadline=SystemClock.elapsedRealtime()+120000;libraryChooserPending=true;}
        void openLibrary(){worker.execute(()->{if(page!=Page.SETTINGS||!canChangePrintMode())return;operationModes.invalidateOperations();clearScene();clearFaceSurface();faceSession.invalidate();if(librarySession!=null&&librarySession.busy())return;page=Page.LIBRARY;emptyLibraryId=UUID.randomUUID().toString();emptyLibraryToken=UUID.randomUUID().toString();openLibraryOwner();publishedKey=null;publish();});}
        private void openLibraryOwner(){
            try{com.triathlon.prep.core.nextday.FaceLibrary.Scope selected=activeFaceScope();
                com.triathlon.prep.nextday.host.NextDayModule module=ensureNextDayModule(selected==null?faceCompatibility():selected);
                if(librarySession!=null)librarySession.close();
                librarySession=new com.triathlon.prep.nextday.host.LibraryHostSession(module,operationModes,new Verification.Clock(){public long elapsedMillis(){return SystemClock.elapsedRealtime();}public long wallMillis(){return System.currentTimeMillis();}},this::canLibrary,s->new com.triathlon.prep.nextday.host.SafFaceTransfer(s,app.getContentResolver()),this::dispatchLibrary,this::libraryChanged,this::commitSourceBank,()->faceSelection!=null&&faceSelection.current()!=null);
                librarySession.restorePort(this::restoreSourceBank,()->faceSelection==null||faceSelection.previous()==null?"":faceSelection.previous().batchId);librarySession.open();
                if(bankBlocked)librarySession.failure("旧索引恢复失败，刷脸已阻断；请明确刷新重新准备，不能沿用旧就绪状态。");
            }catch(Exception|LinkageError unavailable){librarySession=null;}
        }
        private FaceLibraryManagementView.Model libraryModel(){
            if(librarySession!=null)return libraryPublished=librarySession.model(currentRoster);
            return libraryPublished=new FaceLibraryManagementView.Model(Adapters.mode()==Verification.Mode.TEST,currentRoster==null?"未选择赛事":currentRoster.eventName,currentRoster==null?"未选择批次":currentRoster.batchId,Adapters.mode()==Verification.Mode.TEST?"TEST合成库":"百度V9 / LIVE_PHOTO","待核",FaceLibraryManagementView.LibraryState.ABSENT,0,0,false,FaceLibraryManagementView.State.FAILED,currentRoster==null?"请先导入本场名单，或完成来源人脸库选择。":"人脸库准备失败，原库未清理，请核对私有存储及许可。",0,1,0,Collections.emptyList(),null,emptyLibraryId,canLibrary()?emptyLibraryToken:"",1,false,false,false,false,false,canLibrary(),canLibrary(),false);
        }
        void libraryRefresh(String id,String token,long revision){worker.execute(()->{if(!canLibrary())return;if(librarySession!=null){if(!librarySession.refresh(id,token,revision))return;try{librarySession.close();if(nextDayModule!=null){nextDayModule.close();nextDayModule=null;nextDayCameraConfigured=false;checkInEnrollment=null;}bankBlocked=false;openLibraryOwner();if(nextDayModule==null||nextDayModule.engine.readiness()!=com.triathlon.prep.core.nextday.FaceEngine.Readiness.READY)bankBlocked=true;}catch(Exception unavailable){bankBlocked=true;if(librarySession!=null)librarySession.failure("重新准备失败，刷脸保持阻断，原选择未改变。 ");}}else if(emptyLibraryId.equals(id)&&emptyLibraryToken.equals(token)&&revision==1){emptyLibraryToken=UUID.randomUUID().toString();openLibraryOwner();}publishedKey=null;publish();});}
        void libraryExport(String id,String token,long revision,Consumer<String> launch){worker.execute(()->{if(!canLibrary()||librarySession==null)return;com.triathlon.prep.nextday.host.LibraryHostSession owner=librarySession;try{com.triathlon.prep.nextday.host.FaceLibraryTransferSession.Offer offer=owner.beginExport(id,token,revision);if(offer!=null){rememberChooser(owner,offer.id);main.post(()->{if(chooserPauseValid()&&sink!=null)launch.accept(offer.id);else cancelLibraryChoice();});}}catch(Exception error){owner.failure("导出未开始，请核对库状态后重新操作。 ");}});}
        void libraryChooseImport(String id,String token,long revision,Runnable input){worker.execute(()->{if(canLibrary()&&librarySession!=null&&librarySession.beginChooseImport(id,token,revision)){com.triathlon.prep.nextday.host.LibraryHostSession owner=librarySession;main.post(()->{if(canLibrary()&&librarySession==owner&&!owner.closed())input.run();else cancelLibraryChoice();});}});}
        void libraryImportKey(String code,Consumer<String> launch){worker.execute(()->{if(!canLibrary()||librarySession==null)return;com.triathlon.prep.nextday.host.LibraryHostSession owner=librarySession;try{com.triathlon.prep.nextday.host.FaceLibraryTransferSession.Offer offer=owner.inputKey(code);rememberChooser(owner,offer.id);main.post(()->{if(chooserPauseValid()&&sink!=null)launch.accept(offer.id);else cancelLibraryChoice();});}catch(Exception error){owner.cancelChoice();owner.failure("传递密钥无效或本次请求已失效，原库保持不变。 ");}});}
        void cancelLibraryChoice(){libraryChooserPending=false;dispatchLibrary(()->{if(librarySession!=null&&!librarySession.closed())librarySession.cancelChoice();});}
        void libraryFileSelected(String id,Uri uri,boolean exporting,Consumer<String> keyDisplay){libraryChooserPending=false;worker.execute(()->{
            com.triathlon.prep.nextday.host.LibraryHostSession owner=libraryChooserOwner;boolean current=owner!=null&&owner==librarySession&&!owner.closed()&&page==Page.LIBRARY&&libraryChooserId.equals(id)&&SystemClock.elapsedRealtime()<=libraryChooserDeadline&&operationModes.allows(libraryChooserMode,libraryChooserEpoch);
            libraryChooserId="";libraryChooserOwner=null;
            if(!current){if(exporting&&uri!=null)deleteAbandonedFaceOutput(uri);return;}
            if(uri==null){owner.cancelChoice();return;}
            try{if(exporting)owner.writeExport(id,uri.toString());else owner.readImport(id,uri.toString());}
            catch(Exception error){owner.failure("文件操作未开始，原库保持不变。 ");if(exporting)deleteAbandonedFaceOutput(uri);}
        });}
        private void deleteAbandonedFaceOutput(Uri uri){try{exportIo.execute(()->{try{android.provider.DocumentsContract.deleteDocument(app.getContentResolver(),uri);}catch(Exception ignored){dispatchLibrary(()->{if(librarySession!=null&&!librarySession.closed())librarySession.failure("取消的输出文件未确认清除，不可作为有效库使用。 ");});}});}catch(java.util.concurrent.RejectedExecutionException ignored){}}
        void maybeShowExportKey(Consumer<String> show){libraryKeyPresenter=show;worker.execute(()->{if(!canLibrary()||librarySession==null||!librarySession.keyReady())return;String key=librarySession.takeExportKey();if(!key.isEmpty()){com.triathlon.prep.nextday.host.LibraryHostSession owner=librarySession;long epoch=operationModes.snapshot().epoch;main.post(()->{if(canLibrary()&&librarySession==owner&&!owner.closed()&&operationModes.snapshot().epoch==epoch)show.accept(key);});}});}
        void libraryConfirm(String id,String preflight,String token,long revision,boolean confirmed){worker.execute(()->{if(!canLibrary()||librarySession==null)return;com.triathlon.prep.nextday.host.LibraryHostSession owner=librarySession;boolean accepted=false;bankSwitching=true;try{accepted=owner.confirmImport(id,preflight,token,revision,confirmed);}finally{bankSwitching=false;}if(accepted){boolean imported=owner.imported();enrollmentHook.clear();openLibraryOwner();if(!imported&&librarySession!=null)librarySession.failure(bankBlocked?"旧索引恢复失败，刷脸已阻断；请明确刷新重新准备。":"来源库未选择成功，旧选择保持不变；已重新准备旧库。 ");publishedKey=null;publish();}});}
        void libraryDiscard(String id,String preflight,String token,long revision){worker.execute(()->{if(canLibrary()&&librarySession!=null)librarySession.cancelPreview(id,preflight,token,revision);});}
        void libraryClear(String id,String token,long revision,boolean confirmed){worker.execute(()->{if(canLibrary()&&librarySession!=null&&librarySession.clear(id,token,revision,confirmed))enrollmentHook.clear();});}
        void libraryRestore(String id,String token,long revision,boolean confirmed){worker.execute(()->{
            if(!canLibrary()||librarySession==null)return;bankSwitching=true;boolean accepted=false;
            try{accepted=librarySession.restorePrevious(id,token,revision,confirmed);}finally{bankSwitching=false;}
            if(accepted){boolean restored=librarySession.restored();enrollmentHook.clear();openLibraryOwner();if(!restored&&librarySession!=null)librarySession.failure(bankBlocked?"旧索引恢复失败，刷脸已阻断。":"恢复未完成，原选择保持不变。 ");publishedKey=null;publish();}
        });}
        void libraryPage(int page,String id,String token,long revision){worker.execute(()->{if(canLibrary()&&librarySession!=null)librarySession.page(page,id,token,revision);});}
        void libraryBack(String id,String token,long revision){if(page!=Page.LIBRARY)return;com.triathlon.prep.nextday.host.LibraryHostSession owner=librarySession;boolean accepted=owner!=null?owner.takeBack(id,token,revision):emptyLibraryId.equals(id)&&emptyLibraryToken.equals(token)&&revision==1;if(!accepted)return;operationModes.invalidateOperations();cancelLibraryWork();worker.execute(()->{page=Page.MAIN;publishedKey=null;publish();});}
        private void nextDayChanged(){if(!shuttingDown&&nextDayQueued.compareAndSet(false,true))try{worker.execute(()->{nextDayQueued.set(false);publishedKey=null;publish();});}catch(java.util.concurrent.RejectedExecutionException closed){nextDayQueued.set(false);}}
        private com.triathlon.prep.nextday.host.NextDayModule ensureNextDayModule(String batch)throws java.io.IOException{
            com.triathlon.prep.core.nextday.FaceLibrary.Scope scope=new com.triathlon.prep.core.nextday.FaceLibrary.Scope(Adapters.mode(),batch,NextDayFactory.fingerprint(app),"LIVE_PHOTO",512);
            return ensureNextDayModule(scope);
        }
        private com.triathlon.prep.nextday.host.NextDayModule ensureNextDayModule(com.triathlon.prep.core.nextday.FaceLibrary.Scope scope)throws java.io.IOException{
            if(nextDayModule!=null&&!nextDayModule.library.snapshot().scope.equals(scope)){nextDayModule.close();nextDayModule=null;checkInEnrollment=null;nextDayCameraConfigured=false;}
            if(nextDayModule==null){nextDayModule=NextDayFactory.create(app,scope,sceneSlot,operationModes,new Verification.Clock(){public long elapsedMillis(){return SystemClock.elapsedRealtime();}public long wallMillis(){return System.currentTimeMillis();}},faceSurface);((NextDayCapture)nextDayModule.capture).onChanged(this::nextDayChanged);}
            nextDayModule.prepare();checkInEnrollment=nextDayModule.enrollment;return nextDayModule;
        }
        private com.triathlon.prep.core.nextday.FaceEnrollment enrollmentForSaved(Verification.Snapshot saved)throws java.io.IOException{return ensureNextDayModule(saved.rosterBatchId).enrollment;}
        private com.triathlon.prep.core.nextday.FaceLibrary.Scope faceCompatibility()throws java.io.IOException{
            return new com.triathlon.prep.core.nextday.FaceLibrary.Scope(Adapters.mode(),"00000000-0000-0000-0000-000000000000",NextDayFactory.fingerprint(app),"LIVE_PHOTO",512);
        }
        private com.triathlon.prep.core.nextday.FaceLibrary.Scope activeFaceScope()throws java.io.IOException{
            String local=currentRoster==null?"":currentRoster.batchId;
            if(faceSelection==null||!local.equals(selectionRosterBatch)){
                com.triathlon.prep.core.nextday.FaceLibrary.Scope compatibility=faceCompatibility();
                java.io.File folder=new java.io.File(app.getNoBackupFilesDir(),"nextday-selection/"+Adapters.mode().name());
                if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("Selection folder unavailable");
                javax.crypto.SecretKey key=com.triathlon.prep.nextday.storage.FaceLibraryKeyStore.loadOrCreate(app,Adapters.mode());
                com.triathlon.prep.core.nextday.FaceLibrary.Scope fallback=local.isEmpty()?null:new com.triathlon.prep.core.nextday.FaceLibrary.Scope(Adapters.mode(),local,compatibility.modelFingerprint,"LIVE_PHOTO",512);
                faceSelection=new com.triathlon.prep.core.nextday.FaceLibrarySelection(new com.triathlon.prep.nextday.host.EncryptedFaceSelectionStore(new java.io.File(folder,"active.dat"),key,app.getPackageName(),Adapters.mode()),compatibility,fallback);selectionRosterBatch=local;
            }
            return faceSelection.current();
        }
        private static boolean sameFaceParameters(com.triathlon.prep.core.nextday.FaceEngine.Parameters a,com.triathlon.prep.core.nextday.FaceEngine.Parameters b){
            return a!=null&&b!=null&&a.calibrated&&b.calibrated&&a.revision==b.revision&&Double.compare(a.matchThreshold,b.matchThreshold)==0&&Double.compare(a.minimumMargin,b.minimumMargin)==0&&a.maximumFrameAgeMillis==b.maximumFrameAgeMillis;
        }
        private com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard lockedBankGuard(com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard guard){
            return new com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard(){
                public boolean current(){return guard.current();}
                public void commit(com.triathlon.prep.core.nextday.FaceLibrary.Mutation mutation)throws java.io.IOException{
                    synchronized(operationModes){if(!current())throw new java.io.IOException("Library activation revoked");mutation.run();}
                }
            };
        }
        private void commitSourceBank(com.triathlon.prep.core.nextday.FaceLibrary.Snapshot source,com.triathlon.prep.core.nextday.FaceEngine.Parameters pinned,com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard lease)throws java.io.IOException{
            activeFaceScope();faceSelection.validate(source.scope);
            com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard current=()->canLibrary()&&lease.current();
            if(source.scope.equals(faceSelection.current())){
                synchronized(operationModes){if(!current.current()||!sameFaceParameters(pinned,nextDayModule.engine.parameters()))throw new java.io.IOException("Import authority changed");}
                nextDayModule.library.importSnapshot(source,lockedBankGuard(()->current.current()&&sameFaceParameters(pinned,nextDayModule.engine.parameters())));return;
            }
            boolean wasSwitching=bankSwitching;bankSwitching=true;
            com.triathlon.prep.nextday.host.FaceBankSwitch switching=new com.triathlon.prep.nextday.host.FaceBankSwitch(faceSelection,new com.triathlon.prep.nextday.host.FaceBankSwitch.Backend(){
                public void stage(com.triathlon.prep.core.nextday.FaceLibrary.Snapshot snapshot,com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard guard)throws java.io.IOException{
                    clearScene();clearFaceSurface();faceSession.invalidate();
                    com.triathlon.prep.nextday.host.NextDayModule target=ensureNextDayModule(snapshot.scope);
                    if(!target.prepare()||!sameFaceParameters(pinned,target.engine.parameters()))throw new java.io.IOException("Source engine preparation changed");
                    target.library.importSnapshot(snapshot,lockedBankGuard(()->guard.current()&&sameFaceParameters(pinned,target.engine.parameters())));
                    if(target.library.status()!=com.triathlon.prep.core.nextday.FaceLibrary.Status.READY&&target.library.status()!=com.triathlon.prep.core.nextday.FaceLibrary.Status.EMPTY)throw new java.io.IOException("Source index unavailable");
                }
                public void restore(com.triathlon.prep.core.nextday.FaceLibrary.Scope previous)throws java.io.IOException{
                    if(nextDayModule!=null){nextDayModule.close();nextDayModule=null;nextDayCameraConfigured=false;checkInEnrollment=null;}
                    if(previous!=null&&!ensureNextDayModule(previous).prepare())throw new java.io.IOException("Previous index restoration failed");
                }
                public void block(){bankBlocked=true;faceSession.backend(null,null,false,0,0,0);}
            },operationModes);
            try{switching.confirm(source,()->current.current()&&nextDayModule!=null&&sameFaceParameters(pinned,nextDayModule.engine.parameters()));bankBlocked=false;}
            finally{bankSwitching=wasSwitching;}
        }
        private void restoreSourceBank(com.triathlon.prep.core.nextday.FaceEngine.Parameters pinned,com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard lease)throws java.io.IOException{
            activeFaceScope();
            com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard current=()->canLibrary()&&lease.current()&&nextDayModule!=null&&sameFaceParameters(pinned,nextDayModule.engine.parameters());
            com.triathlon.prep.nextday.host.FaceBankSwitch restore=new com.triathlon.prep.nextday.host.FaceBankSwitch(faceSelection,new com.triathlon.prep.nextday.host.FaceBankSwitch.Backend(){
                public void stage(com.triathlon.prep.core.nextday.FaceLibrary.Snapshot ignored,com.triathlon.prep.core.nextday.FaceLibrary.MutationGuard guard)throws java.io.IOException{throw new java.io.IOException("Restore loads existing bank only");}
                public void restore(com.triathlon.prep.core.nextday.FaceLibrary.Scope scope)throws java.io.IOException{
                    clearScene();clearFaceSurface();faceSession.invalidate();
                    if(nextDayModule!=null){nextDayModule.close();nextDayModule=null;nextDayCameraConfigured=false;checkInEnrollment=null;}
                    if(scope!=null){com.triathlon.prep.nextday.host.NextDayModule target=ensureNextDayModule(scope);if(!target.prepare()||!sameFaceParameters(pinned,target.engine.parameters()))throw new java.io.IOException("Restore index/parameters unconfirmed");}
                }
                public void block(){bankBlocked=true;faceSession.backend(null,null,false,0,0,0);}
            },operationModes);
            try{restore.restore(current);bankBlocked=false;}catch(java.io.IOException failure){if(restore.state()==com.triathlon.prep.nextday.host.FaceBankSwitch.State.BLOCKED_RESTORE)bankBlocked=true;throw failure;}
        }
        private void refreshFaceBackend(){
            if(!faceAllowed()||!faceSession.enabled())return;
            try{
                com.triathlon.prep.core.nextday.FaceLibrary.Scope scope=activeFaceScope();
                if(scope==null||bankBlocked){faceSession.backend(null,null,false,0,0,0);return;}
                com.triathlon.prep.nextday.host.NextDayModule module=ensureNextDayModule(scope);NextDayCapture capture=(NextDayCapture)module.capture;
                boolean prepared=module.engine.readiness()==com.triathlon.prep.core.nextday.FaceEngine.Readiness.READY;
                if(prepared&&!nextDayCameraConfigured){capture.configure();nextDayCameraConfigured=true;}
                long epoch=operationModes.snapshot().epoch,view=faceSurface.revision();
                if(prepared&&module.library.status()==com.triathlon.prep.core.nextday.FaceLibrary.Status.READY&&faceSession.idleForPreview()&&(Adapters.mode()==Verification.Mode.TEST||faceSurface.ready()))capture.preparePreview(faceSession.operationId(),epoch,view);
                faceSession.backend(module.library,module.engine,prepared&&capture.previewReady(faceSession.operationId(),epoch,view),view,capture.bufferWidth(),capture.bufferHeight());
            }catch(Exception|LinkageError error){faceSession.backend(null,null,false,0,0,0);}
        }
        private final NextDayScreenSession faceSession=new NextDayScreenSession(Adapters.mode());
        private String operationModeNotice="";
        private long pendingModeRelease;
        private boolean intakeReleaseConfirmed,displayDetachConfirmed;
        private VerificationModeSession.Reply modeReleaseReply;
        private long modeReleaseDeadline;
        private boolean checkinAllowed(){VerificationModeSession.Snapshot m=operationModes.snapshot();return operationModes.allows(VerificationModeSession.Mode.CHECKIN,m.epoch);}
        private boolean faceAllowed(){VerificationModeSession.Snapshot m=operationModes.snapshot();return operationModes.allows(VerificationModeSession.Mode.FACE,m.epoch)&&sink!=null&&page==Page.MAIN&&!routeBlocked;}
        void requestOperationMode(VerificationModeControl.OperationMode mode,String token,long revision){
            if(shuttingDown||sink==null||page!=Page.MAIN||mode==null)return;
            if(!operationModes.request(VerificationModeSession.Mode.valueOf(mode.name()),token,revision))return;
            clearScene();
            clearFaceSurface();
            cancelLibraryWork();
            try{worker.execute(()->{publishedKey=null;publish();});}
            catch(java.util.concurrent.RejectedExecutionException closed){/* Closing Host already revoked operations. */}
        }
        private void releaseForMode(VerificationModeSession.Mode old,VerificationModeSession.Mode target,long epoch,VerificationModeSession.Reply reply){
            operationModeNotice="";
            if(enrollmentHook!=null)enrollmentHook.clear();
            nextDayReleaseConfirmed=true;
            if(nextDayModule!=null)try{nextDayModule.close();nextDayModule=null;checkInEnrollment=null;nextDayCameraConfigured=false;}catch(Exception|LinkageError release){nextDayReleaseConfirmed=false;}
            pendingModeRelease=epoch;modeReleaseReply=reply;modeReleaseDeadline=SystemClock.elapsedRealtime()+10000;
            intakeReleaseConfirmed=intake==null;displayDetachConfirmed=false;faceSession.invalidate();receiptPermit.invalidate();passportPermit.invalidate();
            if(autoPrint!=null)autoPrint.invalidate();if(speech!=null)speech.cancel();
            if(runtime!=null){runtime.printing.cancel();if(runtime.inbox!=null)runtime.inbox.clear();}
            if(flow!=null)flow.cancel();
            if(intake!=null)intake.pause(ok->{try{worker.execute(()->{if(pendingModeRelease!=epoch)return;intakeReleaseConfirmed=ok;if(!ok)finishModeRelease(epoch,false);else checkModeRelease(epoch);});}catch(java.util.concurrent.RejectedExecutionException ignored){}});
            publishedKey=null;publish();
            main.post(()->{if(runtime!=null&&runtime.display!=null)runtime.display.detach();try{worker.execute(()->{if(pendingModeRelease!=epoch)return;displayDetachConfirmed=true;checkModeRelease(epoch);});}catch(java.util.concurrent.RejectedExecutionException ignored){}});
        }
        private void checkModeRelease(long epoch){
            if(pendingModeRelease!=epoch||shuttingDown)return;
            boolean previewReleased=runtime==null||runtime.display==null?Adapters.mode()==Verification.Mode.TEST||runtime==null||!runtime.canBegin():runtime.display.previewResourcesReleased();
            if(intakeReleaseConfirmed&&displayDetachConfirmed&&previewReleased&&nextDayReleaseConfirmed&&faceSurface.released()&&(librarySession==null||!librarySession.busy())){finishModeRelease(epoch,true);return;}
            if(SystemClock.elapsedRealtime()>=modeReleaseDeadline){finishModeRelease(epoch,false);return;}
            main.postDelayed(()->{try{worker.execute(()->checkModeRelease(epoch));}catch(java.util.concurrent.RejectedExecutionException ignored){}},100);
        }
        private void finishModeRelease(long epoch,boolean ok){
            if(pendingModeRelease!=epoch)return;pendingModeRelease=0;VerificationModeSession.Reply reply=modeReleaseReply;modeReleaseReply=null;
            operationModeNotice=ok?"":"资源释放未确认；请检查设备后明确重试，未启用目标模式。";reply.released(ok);publishedKey=null;publish();
        }
        private VerificationModeControl.Model operationModeModel(){
            VerificationModeSession.Snapshot m=operationModes.snapshot();String notice=operationModeNotice;
            if(m.state==VerificationModeSession.State.STABLE&&m.currentMode==VerificationModeSession.Mode.CHECKIN&&enrollmentHook!=null){com.triathlon.prep.core.nextday.CheckInEnrollmentCoordinator.Result enrolled=enrollmentHook.result();
                if(enrolled.state==com.triathlon.prep.core.nextday.CheckInEnrollmentCoordinator.State.ENROLLED)notice="报到已保存，本次现场照已登记人脸库。";
                else if(enrolled.state==com.triathlon.prep.core.nextday.CheckInEnrollmentCoordinator.State.NOT_ENROLLED)notice="报到已保存，人脸库未登记；"+enrollmentReason(enrolled.reason);
            }
            return new VerificationModeControl.Model(
             VerificationModeControl.OperationMode.valueOf(m.currentMode.name()),VerificationModeControl.OperationMode.valueOf(m.targetMode.name()),
             VerificationModeControl.SwitchState.valueOf(m.state.name()),notice,m.requestToken,m.revision,
             sink!=null&&runtime!=null&&page==Page.MAIN&&!shuttingDown&&m.state!=VerificationModeSession.State.SWITCHING);
        }
        private String enrollmentReason(com.triathlon.prep.core.nextday.FaceEnrollment.Reason reason){
            switch(reason){case STALE_SCENE:return "本次现场照已失效，请重新核验。";case ENGINE_NOT_READY:case PARAMETERS_UNVERIFIED:case PARAMETERS_CHANGED:return "请先核实百度许可、模型与核验参数。";case QUALITY_FAILED:case FEATURE_MISSING:return "现场照未通过登记质量检查。";case SDK_OR_STORE_FAILED:return "登记处理失败，可在本次现场照有效时明确重试。";default:return "赛事或人脸库条件尚未就绪。";}
        }
        void facePrimary(String id,String token,long revision){worker.execute(()->{NextDayFaceView.PrimaryAction action=faceSession.action();if(!faceSession.primary(id,token,revision,faceAllowed()))return;publishedKey=null;publish();if(nextDayModule!=null&&(action==NextDayFaceView.PrimaryAction.START_CHECK||action==NextDayFaceView.PrimaryAction.RETRY_SAVE)){com.triathlon.prep.nextday.host.FaceVerificationWorkflow.Result result=action==NextDayFaceView.PrimaryAction.RETRY_SAVE?nextDayModule.workflow.retrySave(id):nextDayModule.workflow.run(id);faceSession.completed(result);publishedKey=null;publish();}});}
        void faceCancel(String id,String token,long revision){if(!faceAllowed()||!faceSession.allowsCancel(id,token,revision))return;operationModes.invalidateOperations();clearFaceSurface();worker.execute(()->{if(nextDayModule!=null)nextDayModule.cancel(id);faceSession.invalidate();publishedKey=null;publish();});}
        void faceNext(String id,String token,long revision){worker.execute(()->{if(faceSession.next(id,token,revision,faceAllowed())){operationModes.invalidateOperations();clearFaceSurface();publishedKey=null;publish();}});}
        void faceManual(String id,String token,long revision){worker.execute(()->{if(faceSession.manual(id,token,revision,faceAllowed())){operationModeNotice="请工作人员核对本次结果；需要人证核验时请明确切换报到模式。";publishedKey=null;publish();}});}
        void faceSurfaceAvailable(String token,long revision,android.view.Surface surface,int width,int height){if(!faceAllowed()||!faceSession.matchesSurfaceToken(token)||width<1||height<1)return;try{faceSurface.available(token,revision,surface);nextDayChanged();}catch(RuntimeException error){clearFaceSurface();}}
        void faceSurfaceDestroyed(String token,long revision){if(!faceSurface.matches(token,revision))return;boolean current=faceSession.matchesSurfaceToken(token);if(current)operationModes.invalidateOperations();faceSurface.destroyed(token,revision);if(current)nextDayChanged();}
        void faceSurfaceFrame(String token,long revision,long timestamp){if(faceAllowed()&&faceSession.matchesSurfaceToken(token))faceSurface.frame(token,revision,timestamp);}
        void faceInvalidate(){operationModes.invalidateOperations();clearScene();clearFaceSurface();worker.execute(()->{if(faceAllowed()){if(nextDayModule!=null)nextDayModule.cancel(faceSession.operationId());faceSession.invalidate();publishedKey=null;publish();}});}
        void faceBack(String id,String token,long revision){worker.execute(()->{if(faceSession.back(id,token,revision,faceAllowed())){operationModes.invalidateOperations();clearFaceSurface();if(nextDayModule!=null)nextDayModule.cancel(id);page=Page.SETTINGS;publishedKey=null;publish();}});}
        void openFaceEntry(){worker.execute(()->{if(page!=Page.SETTINGS)return;page=Page.MAIN;operationModeNotice="请在顶栏手动选择需要的核验模式。";publishedKey=null;publish();});}
        private String businessKey="";
        private int syntheticIndex;
        private volatile boolean shuttingDown;
        private volatile boolean routeBlocked;
        private SpeechSetting speechSettings;
        private SpeechPolicy speech;
        private SpeechPort speechPort;
        private SpeechControl.SaveStatus speechStatus=SpeechControl.SaveStatus.LOADING;
        private String speechNotice="";
        private long speechRevision;
        private void speechChanged(){if(!shuttingDown)try{worker.execute(()->{speechRevision++;publishedKey=null;publish();});}catch(java.util.concurrent.RejectedExecutionException ignored){}}
        private void initializeSpeech(){
            try{speechSettings=new SpeechSetting(new SpeechPreferences(app));speechPort=new AndroidSpeechPort(app,this::speechChanged);speech=new SpeechPolicy(speechSettings.current(),speechPort,this::speechChanged);speechStatus=speechSettings.readFailed()?SpeechControl.SaveStatus.FAILED:SpeechControl.SaveStatus.READY;if(speechSettings.readFailed())speechNotice="已保存语音设置读取失败；自动播报关闭，可重新保存设置。";}
            catch(RuntimeException|LinkageError ex){speechStatus=SpeechControl.SaveStatus.FAILED;speechNotice="语音初始化失败；核验可照常进行。";}
            speechRevision++;
        }
        private SpeechControl.Model speechModel(){
            SpeechSettings confirmed=speechSettings==null?SpeechSettings.defaults():speechSettings.current();
            SpeechPort.Readiness ready=speechPort==null?SpeechPort.Readiness.FAILED:speechPort.readiness();
            boolean edit=sink!=null&&page==Page.SETTINGS&&speechSettings!=null&&canChangePrintMode()&&speechStatus!=SpeechControl.SaveStatus.LOADING&&speechStatus!=SpeechControl.SaveStatus.SAVING;
            return new SpeechControl.Model(confirmed,speechStatus,ready,speech==null?SpeechPort.Progress.IDLE:speech.progress(),speechNotice,speechRevision,edit,edit&&ready==SpeechPort.Readiness.READY&&confirmed.volumePercent>0);
        }
        void changeSpeechEnabled(boolean enabled){saveSpeech(old->new SpeechSettings(enabled,old.volumePercent,old.texts()));}
        void changeSpeechVolume(int volume){saveSpeech(old->new SpeechSettings(old.enabled,volume,old.texts()));}
        void changeSpeechTexts(Map<SpeechSettings.Scene,String> drafts){final Map<SpeechSettings.Scene,String> copy=drafts==null?null:new LinkedHashMap<>(drafts);saveSpeech(old->new SpeechSettings(old.enabled,old.volumePercent,copy));}
        private void saveSpeech(UnaryOperator<SpeechSettings> change){worker.execute(()->{
            if(speechSettings==null||sink==null||page!=Page.SETTINGS||!canChangePrintMode()){speechStatus=SpeechControl.SaveStatus.FAILED;speechNotice="当前不能修改语音设置；请返回设置页重试。";speechRevision++;publish();return;}
            try{SpeechSettings next=change.apply(speechSettings.current());speechStatus=SpeechControl.SaveStatus.SAVING;speechNotice="";speechRevision++;publish();
                if(speechSettings.save(next)){if(speech!=null)speech.settings(next);speechStatus=SpeechControl.SaveStatus.READY;speechNotice="语音设置已保存；开启仅影响后续新事件。";}
                else{speechStatus=SpeechControl.SaveStatus.FAILED;speechNotice="语音设置保存失败；沿用已确认值。";}
            }catch(IllegalArgumentException ex){speechStatus=SpeechControl.SaveStatus.FAILED;speechNotice="文字最多100个Unicode字符，不能包含控制字符；音量须为0–100%。";}
            finally{speechRevision++;publishedKey=null;publish();}
        });}
        void previewSpeech(SpeechSettings.Scene scene,String draft,int volume){worker.execute(()->{
            if(speech==null||sink==null||page!=Page.SETTINGS||!canChangePrintMode())return;
            try{speech.preview(scene,draft,volume,Adapters.mode()==Verification.Mode.TEST,true);speechNotice="人工试听当前文字；未保存设置或创建核验记录。";}
            catch(IllegalArgumentException ex){speechNotice="试听文字或音量无效；未提交语音。";}
            speechRevision++;publishedKey=null;publish();
        });}
        private volatile boolean neutralActivity;
        private final PrintActionPermit receiptPermit=new PrintActionPermit(),passportPermit=new PrintActionPermit();
        private void intakeChanged(){if(!shuttingDown)try{worker.execute(()->{publishedKey=null;publish();});}catch(java.util.concurrent.RejectedExecutionException ignored){}}
        private void admitted(AutoCardIntake.Admission admission){
            Sink expected=sink;
            long admissionEpoch=operationModes.snapshot().epoch;
            main.post(()->{
                if(shuttingDown||!operationModes.allows(VerificationModeSession.Mode.CHECKIN,admissionEpoch)||sink==null||sink!=expected){admission.close();return;}
                Runnable prepare=beforeAdmission;if(prepare!=null)prepare.run();
                try{worker.execute(()->acceptCard(admission,expected,admissionEpoch));}catch(java.util.concurrent.RejectedExecutionException ex){admission.close();}
            });
        }
        private void acceptCard(AutoCardIntake.Admission admission,Sink expected,long admissionEpoch){
            if(!operationModes.allows(VerificationModeSession.Mode.CHECKIN,admissionEpoch)||sink==null||sink!=expected||page!=Page.MAIN||runtime==null||flow==null||!canChangePrintMode()||nameSaving.get()||modeSaving.get()){
                admission.close();if(intake!=null)intake.pause();return;
            }
            Verification.Snapshot old=flow.snapshot();
            if(old.stage!=Verification.Stage.IDLE&&old.saveState!=Verification.SaveState.SAVED){admission.close();intake.pause();return;}
            try{
                clearScene();
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
            boolean allowed=checkinAllowed()&&sink!=null&&!routeBlocked&&cameraReady&&page==Page.MAIN&&currentRoster!=null&&!rosterCorrupt&&!nameSaving.get()&&!modeSaving.get();
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
            AutoCardIntake i=intake;if(!checkinAllowed()||i==null)return false;
            AutoCardIntake.State state=i.snapshot().state;return state==AutoCardIntake.State.READY||state==AutoCardIntake.State.BLOCKED_SAME_CARD;
        }
        void recheckCard(){worker.execute(()->{if(checkinAllowed()&&intake!=null&&page==Page.MAIN&&canChangePrintMode()&&flow.snapshot().saveState!=Verification.SaveState.FAILED)intake.explicitReverify();publish();});}
        private AutomaticIntakeControl.Model intakeModel(){
            boolean test=Adapters.mode()==Verification.Mode.TEST;
            AutomaticIntakeControl.State state=AutomaticIntakeControl.State.DISABLED;
            String notice=currentRoster==null?"请先导入本场名单。":"未配置自动读卡；请核对设备与授权。";
            if(intake!=null){AutoCardIntake.Snapshot i=intake.snapshot();switch(i.state){
                case READY:state=flow.snapshot().stage==Verification.Stage.FINISHED?AutomaticIntakeControl.State.WAITING_NEXT:AutomaticIntakeControl.State.WAITING_CARD;notice=test?"测试按钮仅投递虚构卡；核验与保存由主流程自动完成。":"刷卡后自动核验，无需点击开始。";break;
                case BLOCKED_SAME_CARD:state=AutomaticIntakeControl.State.SAME_CARD;notice=flow.snapshot().saveState==Verification.SaveState.SAVED&&CheckIn.trustedSuccess(flow.snapshot().result)?"该选手已报到，请勿重复办理；本次未自动打印。":"重复卡未新建记录；首次失败可由工作人员明确重试。";break;
                case BUSY:case ADMITTING:state=flow.snapshot().saveState==Verification.SaveState.FAILED?AutomaticIntakeControl.State.BLOCKED:AutomaticIntakeControl.State.BUSY;notice=flow.snapshot().saveState==Verification.SaveState.FAILED?"保存失败，请先重试保存；下一张卡未放行。":"当前核验或打印尚未结束，新卡不会排队。";break;
                case FAULT:state=AutomaticIntakeControl.State.BLOCKED;notice="读卡初始化、持久化或释放失败；已停止自动重开，请检查设备。";break;
                default:notice=currentRoster==null?"请先导入本场名单。":routeBlocked?"显示器已变更，请重新明确分配屏幕角色。":Adapters.mode()==Verification.Mode.LIVE&&app.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED?"请先授予相机权限，自动接卡尚未开始。":"自动接卡暂未就绪。";break;
            }}
            return new AutomaticIntakeControl.Model(state,test,state==AutomaticIntakeControl.State.SAME_CARD&&canChangePrintMode()&&!nameSaving.get()&&!modeSaving.get(),notice);
        }
        private ReceiptNameSetting receiptNames;
        private ReceiptTemplateSetting templates;
        private com.triathlon.prep.core.ManualRecordStore manualStore;private ManualPassportCheck manualCheck;
        private final AtomicBoolean manualBusy=new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicLong manualQueryEpoch=new java.util.concurrent.atomic.AtomicLong();
        private volatile long manualSelectionEpoch=-1;
        private ManualPassportCheck.State manualBusyState=ManualPassportCheck.State.SEARCHING;
        private String manualNotice="";private long manualRevision;
        private volatile PassportCheckView.Model passportModel;
        private ReceiptNameSetting.Store templateNameStore;
        private final AtomicBoolean templateSaving=new AtomicBoolean();
        private ReceiptTemplateSettingsView.State templateState=ReceiptTemplateSettingsView.State.READY;
        private String templateNotice="只修改模板；名单值和核验勾选由系统绑定。修改预览不会保存或打印。";
        private long templateRevision;
        private volatile ReceiptTemplateSettingsView.Model templateModel=new ReceiptTemplateSettingsView.Model(ReceiptTemplate.defaults(),ReceiptTemplateSettingsView.State.READY,"正在读取模板。",0,false,false);
        private final ReceiptBitmapRenderer receiptRenderer=new ReceiptBitmapRenderer();
        private final AtomicBoolean nameSaving=new AtomicBoolean();
        private ReceiptNameControl.Status nameStatus=ReceiptNameControl.Status.LOADING;
        private volatile ReceiptNameControl.Model nameModel=new ReceiptNameControl.Model("",ReceiptNameControl.Status.LOADING,40,false,"正在读取小票名称设置。");
        private long nameRevision;
        private final AtomicBoolean modeSaving=new AtomicBoolean();
        private final AtomicBoolean printSwitchSaving=new AtomicBoolean();
        private PrintModeControl.Status printSwitchStatus=PrintModeControl.Status.LOADING;
        private Boolean requestedPrintEnabled;
        private volatile PrintModeControl.Status modeStatus=PrintModeControl.Status.LOADING;
        private volatile PrintModeControl.Model modeModel=new PrintModeControl.Model(PrintModeControl.Mode.MANUAL,null,PrintModeControl.Status.LOADING,false,"正在读取打印设置。",false,null,PrintModeControl.Status.LOADING,false,false);
        private PrintModeControl.Mode requestedMode;
        private long modeRevision;
        private Verification.RecordStore store;
        private LocalRecordDatabase database;
        private DeviceConfigSession deviceConfig;
        private volatile DeviceConfigSession.Snapshot configSnapshot;
        private final AtomicBoolean configSaving=new AtomicBoolean();
        private String configFailure="";
        private FileRosterStore rosterStore;
        private RosterImportSession rosterImport;
        private volatile RaceRoster currentRoster;
        private boolean rosterCorrupt;
        private volatile boolean cameraPermissionEligible;
        private RosterImportView.State rosterState=RosterImportView.State.IDLE;
        private ExcelRosterReader.WorksheetScope importScope=ExcelRosterReader.WorksheetScope.ALL_WORKSHEETS;
        private String importEvent="",importGroup="",pickToken,rosterMessage="请选择本场Excel名单；全部校验通过后确认导入。";
        private int rosterErrorPage;
        private long rosterRevision;
        private final AtomicBoolean exportBusy=new AtomicBoolean();
        private final java.util.concurrent.ExecutorService exportIo=java.util.concurrent.Executors.newSingleThreadExecutor();
        private volatile ExportJob exportJob;
        private RosterImportView.ExportState exportState=RosterImportView.ExportState.IDLE;
        private String exportNotice="本机 / 当前名单批次；请先读取有效名单。";
        private volatile RosterImportView.ExportModel rosterExportModel=new RosterImportView.ExportModel(RosterImportView.ExportState.IDLE,false,"本机 / 当前名单批次；请先读取有效名单。");
        private static final class ExportJob {
            final String id=UUID.randomUUID().toString();final com.triathlon.prep.core.RosterStatusExport.Snapshot snapshot;
            final AtomicBoolean aborted=new AtomicBoolean();volatile java.io.OutputStream stream;
            ExportJob(com.triathlon.prep.core.RosterStatusExport.Snapshot snapshot){this.snapshot=snapshot;}
            void abort(){aborted.set(true);java.io.OutputStream current=stream;if(current!=null)try{current.close();}catch(Exception ignored){}}
        }
        private String problem, publishedKey;
        private volatile Sink sink;
        private volatile Page page = Page.MAIN;
        private boolean recordsLoading;
        private SettingsView.Model settingsModel;
        private final Context app;
        private DiagnosticsView.Model diagnosticsModel;
        private long diagnosticsRevision;
        private long recordsRevision;
        private RecordsView.Filter filter = RecordsView.Filter.ALL;
        private String selectedSessionId, recordsError;
        private List<Verification.Record> records = Collections.emptyList();
        private int recordsPage,recordsPageCount,recordsTotal;

        Host(Context app) {
            this.app = app;
            worker.execute(() -> {
                if(shuttingDown)return;
                try {
                    PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                    long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                    settingsModel = SettingsPresenter.model(Adapters.mode(), info.packageName, info.versionName, versionCode);
                    try{deviceConfig=new DeviceConfigSession(new FileDeviceConfigStore(new File(app.getFilesDir(),"device-config")));configSnapshot=deviceConfig.snapshot();}
                    catch(java.io.IOException error){configFailure="配置草案存储不可用，未启用执行；请检查私有存储。";}
                    FileRecordStore legacyRecords=new FileRecordStore(new File(app.getFilesDir(),"prep-log"));
                    FileManualRecordStore legacyManual=new FileManualRecordStore(new File(app.getFilesDir(),"manual-passport"));
                    database=new LocalRecordDatabase(new File(app.getFilesDir(),"local-records/records.db"),Adapters.mode(),legacyRecords,legacyManual,PrintModePreferences.legacyReservations(app));
                    store=database.records();manualStore=database.manualRecords();
                    rosterStore=new FileRosterStore(new File(app.getFilesDir(),"race-roster"));rosterImport=new RosterImportSession(rosterStore);reloadRoster();
                    runtime = Adapters.runtime(app,store,
                            new Verification.Clock() {
                                public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
                                public long wallMillis() { return System.currentTimeMillis(); }
                            },rosterStore);
                    flow = runtime.flow;
                    flow.setQualifiedCaptureObserver(sceneSlot);
                    flow.setOperationGuard(new Verification.OperationGuard(){
                        public long pin(){return operationModes.snapshot().epoch;}
                        public boolean current(long lease){return !shuttingDown&&sink!=null&&page==Page.MAIN&&!routeBlocked&&operationModes.allows(VerificationModeSession.Mode.CHECKIN,lease);}
                        public com.triathlon.prep.core.CheckIn.State commit(long lease,Verification.RecordStore target,Verification.Record record)throws java.io.IOException{
                            // An already committing transaction completes before revocation; late SDK work cannot enter it.
                            synchronized(operationModes){if(!current(lease))throw new java.io.IOException("Operation authority revoked");return target.saveCheckIn(record);}
                        }
                    });
                    settingsModel=SettingsPresenter.withRuntime(settingsModel,runtime);
                    ReceiptNamePreferences legacyName=new ReceiptNamePreferences(app);
                    FileReceiptTemplateStore templateStore=new FileReceiptTemplateStore(new File(app.getFilesDir(),"receipt-template"));
                    templates=new ReceiptTemplateSetting(new ReceiptTemplateSetting.Store(){
                        public ReceiptTemplate load()throws Exception{ReceiptTemplate stored=templateStore.load();return stored!=null?stored:ReceiptTemplate.defaults().withText(ReceiptTemplate.TextKey.EVENT_HEADING,legacyName.load());}
                        public boolean save(ReceiptTemplate draft)throws Exception{templateStore.replace(draft);return true;}
                    },"");
                    templateNameStore=new ReceiptNameSetting.Store(){
                        public String load()throws Exception{if(templates.readFailed())throw new java.io.IOException("Template unconfirmed");return templates.confirmed().text(ReceiptTemplate.TextKey.EVENT_HEADING);}
                        public boolean save(String heading){return templates.save(templates.confirmed().withText(ReceiptTemplate.TextKey.EVENT_HEADING,heading));}
                    };
                    receiptNames=new ReceiptNameSetting(templateNameStore);
                    runtime.setReceiptTemplate(templates.confirmed());runtime.printing.setGraphicRenderer(receiptRenderer);
                    templateState=templates.readFailed()?ReceiptTemplateSettingsView.State.FAILED:ReceiptTemplateSettingsView.State.READY;
                    if(templates.readFailed())templateNotice="模板读取失败，未覆盖默认或原标题；请核对后明确保存。打印参数未确认。";
                    templateRevision++;
                    nameStatus=receiptNames.readFailed()?ReceiptNameControl.Status.FAILED:ReceiptNameControl.Status.READY;nameRevision++;
                    autoPrint=new AutoPrintPolicy(new PrintModePreferences(app,database::claimDigest));
                    runtime.printing.setPrintPermission(()->checkinAllowed()&&autoPrint.printingAllowed()&&templates!=null&&!templates.readFailed()&&!templateSaving.get());
                    try{
                        database.counts();
                        manualCheck=new ManualPassportCheck(Adapters.mode(),()->{if(rosterCorrupt)throw new java.io.IOException("Roster unconfirmed");return currentRoster;},manualStore,new Verification.Clock(){public long elapsedMillis(){return SystemClock.elapsedRealtime();}public long wallMillis(){return System.currentTimeMillis();}});
                    }catch(java.io.IOException|RuntimeException error){manualNotice="人工记录存储不可用，不能人工确认或打印。";}
                    modeStatus=autoPrint.settingsFailed()?PrintModeControl.Status.FAILED:PrintModeControl.Status.READY;
                    printSwitchStatus=modeStatus;modeRevision++;
                    cameraPermissionEligible=Adapters.mode()==Verification.Mode.LIVE&&runtime.canBegin();
                    if(runtime.canBegin()&&runtime.inbox!=null&&runtime.cardSource!=null)intake=new AutoCardIntake(Adapters.mode(),runtime.cardSource,new PreferencesCardFence(app,()->currentRoster==null?"":currentRoster.batchId),new AutoCardIntake.Listener(){
                        public void admitted(AutoCardIntake.Admission admission){Host.this.admitted(admission);}
                        public void changed(AutoCardIntake.Snapshot value){intakeChanged();}
                    },15000);
                } catch (Exception | LinkageError ex) { problem = "配置、应用信息或日志初始化失败，核验操作未启用；请检查准备配置与私有存储。"; }
                if(!shuttingDown)initializeSpeech();
                if(!shuttingDown)publish();
            });
        }
        void bind(Sink current) {
            sink = current;
            worker.execute(() -> { publishedKey = null; if (flow != null) flow.tick(); publish(); });
        }
        void unbindAndCancel() {receiptPermit.invalidate();passportPermit.invalidate();
            boolean choosing=chooserPauseValid();if(!choosing){operationModes.invalidateOperations();cancelLibraryWork();}
            libraryKeyPresenter=null;
            clearScene();
            clearFaceSurface();
            sink = null;
            manualQueryEpoch.incrementAndGet();manualSelectionEpoch=-1;
            if(speech!=null)speech.cancel();
            if(intake!=null)intake.pause();
            worker.execute(() -> {if(nextDayModule!=null)nextDayModule.cancel(faceSession.operationId());faceSession.invalidate();if(runtime!=null&&runtime.inbox!=null)runtime.inbox.clear();if(autoPrint!=null)autoPrint.invalidate();if(runtime!=null)runtime.printing.cancel();if(manualCheck!=null)manualCheck.invalidate();if(page==Page.PASSPORT)page=Page.SETTINGS;if(page==Page.RECEIPT)page=Page.MAIN;if (flow != null) flow.cancel(); });
        }
        void refresh() {
            worker.execute(() -> { if (flow != null) flow.tick(); publish(); });
        }
        void refreshDisplay(){worker.execute(()->{publishedKey=null;publish();});}
        private boolean canChangePrintMode(){
            if(autoPrint==null||flow==null||runtime==null||sink==null||faceSession.busy()||bankSwitching)return false;
            Verification.Stage stage=flow.snapshot().stage;
            return (stage==Verification.Stage.IDLE||stage==Verification.Stage.FINISHED)&&runtime.printing.snapshot().status!=ReceiptPrintController.Status.SENDING;
        }
        void changeReceiptName(String draft){
            if(!nameSaving.compareAndSet(false,true))return;
            worker.execute(()->{try{
                if(receiptNames==null||page!=Page.SETTINGS||!canChangePrintMode()){nameStatus=ReceiptNameControl.Status.FAILED;return;}
                nameStatus=ReceiptNameControl.Status.SAVING;nameRevision++;publish();
                boolean saved=receiptNames.save(draft);
                if(saved){runtime.setReceiptTemplate(templates.confirmed());templateState=ReceiptTemplateSettingsView.State.READY;templateRevision++;}
                nameStatus=saved?ReceiptNameControl.Status.READY:ReceiptNameControl.Status.FAILED;
            }finally{nameSaving.set(false);nameRevision++;publishedKey=null;publish();}});
        }
        private boolean manualSending(){ReceiptPrintController.Snapshot job=runtime==null?null:runtime.printing.snapshot();return job!=null&&job.status==ReceiptPrintController.Status.SENDING;}
        void openPassport(){worker.execute(()->{if(!checkinAllowed()||page!=Page.SETTINGS||manualCheck==null||!canChangePrintMode())return;if(intake!=null)intake.pause();if(autoPrint!=null)autoPrint.invalidate();reloadRoster();manualCheck.invalidate();manualCheck.search("",0);manualNotice="";page=Page.PASSPORT;manualRevision++;publishedKey=null;publish();});}
        void searchPassport(String query){long epoch=manualQueryEpoch.incrementAndGet();manualSelectionEpoch=-1;worker.execute(()->{if(page!=Page.PASSPORT||manualCheck==null||manualSending()||epoch!=manualQueryEpoch.get())return;boolean ownsBusy=manualBusy.compareAndSet(false,true);manualBusyState=ManualPassportCheck.State.SEARCHING;manualRevision++;publish();try{reloadRoster();manualCheck.search(query,0);manualNotice="";}finally{if(ownsBusy)manualBusy.set(false);manualRevision++;publishedKey=null;if(epoch==manualQueryEpoch.get())publish();}});}
        void passportPage(String token,int requested){worker.execute(()->{if(page!=Page.PASSPORT||manualCheck==null||manualBusy.get()||manualSending())return;ManualPassportCheck.Snapshot state=manualCheck.snapshot();if(!state.queryToken.equals(token))return;reloadRoster();manualCheck.search(state.query,requested);manualNotice="";manualRevision++;publish();});}
        void selectPassport(String token){long epoch=manualQueryEpoch.get();manualSelectionEpoch=-1;worker.execute(()->{if(page!=Page.PASSPORT||manualCheck==null||manualSending()||epoch!=manualQueryEpoch.get())return;reloadRoster();ManualPassportCheck.Snapshot selected=manualCheck.select(token);manualSelectionEpoch=selected.selected==null?-1:epoch;manualNotice="";manualRevision++;publish();});}
        void confirmPassport(String token){if(!manualBusy.compareAndSet(false,true))return;manualBusyState=ManualPassportCheck.State.SAVING;worker.execute(()->{try{if(page!=Page.PASSPORT||manualCheck==null||manualSending())return;if(manualSelectionEpoch!=manualQueryEpoch.get()){manualNotice="查询已变化，请重新选择，未保存或打印。";return;}manualRevision++;publish();reloadRoster();ManualPassportCheck.Snapshot result=manualCheck.confirm(token);manualNotice=result.notice;if(result.newConfirmation){if(autoPrint==null||!autoPrint.printingAllowed())manualNotice="MANUAL人工核验已保存；打印已关闭，未发起打印。";else manualDispatch(manualCheck.authorizeInitialPrint(token));}}finally{manualBusy.set(false);manualRevision++;publishedKey=null;publish();}});}
        void reprintPassport(String token,String requestToken,long requestRevision,boolean confirmed){if(!manualBusy.compareAndSet(false,true))return;manualBusyState=ManualPassportCheck.State.PRINTING;worker.execute(()->{boolean consumed=false;try{if(sink==null||routeBlocked||page!=Page.PASSPORT||manualCheck==null||manualSending()||autoPrint==null||!autoPrint.printingAllowed())return;if(manualSelectionEpoch!=manualQueryEpoch.get()){manualNotice="查询已变化，请重新选择，未提交补打。";return;}if(!passportPermit.consume(requestToken,requestRevision,true,confirmed))return;consumed=true;reloadRoster();ManualPrintAuthorization auth=manualCheck.authorizeReprint(token);if(auth==null){manualNotice="旧选择或记录已失效，请重新选择。";return;}manualDispatch(auth);}finally{if(consumed)passportPermit.invalidate();manualBusy.set(false);manualRevision++;publishedKey=null;publish();}});}
        private void manualDispatch(ManualPrintAuthorization auth){
            if(manualSelectionEpoch!=manualQueryEpoch.get()){manualNotice="人工结果已保存，但查询已变化，未提交打印；请重新选择后明确补打。";return;}
            if(auth==null||runtime==null||runtime.printLayout==null||templates==null||templates.readFailed()||templateSaving.get()){manualNotice="人工结果已保存，但打印授权/模板/点宽未确认，未提交打印。";return;}
            if(autoPrint==null||!autoPrint.printingAllowed()){manualNotice="人工结果已保存；打印已关闭。";return;}
            if(!autoPrint.beforeManualPassportPrint(auth)){manualNotice="人工结果已保存，但打印防重复记录未确认，未提交；请明确补打。";return;}
            try{if(auth.reprint&&!runtime.printing.renewManualPrint(auth,true)){manualNotice="补打条件未确认，未提交打印。";return;}runtime.printing.submitManual(auth,runtime.printLayout);manualNotice="MANUAL人工核验已保存；打印提交状态以当前回执为准，失败不会自动重复。";}catch(RuntimeException|LinkageError error){manualNotice="人工核验已保存，打印未提交；请核对后明确补打。";}
        }
        void leavePassport(){worker.execute(()->{if(page!=Page.PASSPORT||manualBusy.get())return;if(runtime!=null&&runtime.printing.snapshot().document!=null&&runtime.printing.snapshot().document.manualAuthorization!=null)runtime.printing.cancel();if(manualCheck!=null)manualCheck.invalidate();manualQueryEpoch.incrementAndGet();page=Page.SETTINGS;manualRevision++;publishedKey=null;publish();});}
        private PassportCheckView.Model passportModel(){
            if(manualCheck==null)return null;ManualPassportCheck.Snapshot state=manualCheck.snapshot();if(manualBusy.get())state=manualCheck.presentation(manualBusyState,manualBusyState==ManualPassportCheck.State.SAVING?"正在先保存人工核验，尚未提交打印。":"正在处理，请勿重复操作。");
            ReceiptPrintController.Snapshot job=runtime.printing.snapshot();boolean same=state.record!=null&&job.document!=null&&job.document.manualAuthorization!=null&&state.record.id.equals(job.sessionId);boolean sending=same&&job.status==ReceiptPrintController.Status.SENDING;
            boolean idle=!manualBusy.get()&&!sending&&canChangePrintMode()&&manualSelectionEpoch==manualQueryEpoch.get();boolean printReady=idle&&autoPrint!=null&&autoPrint.printingAllowed()&&templates!=null&&!templates.readFailed()&&runtime.printLayout!=null;
            String notice=manualNotice.isEmpty()?state.notice:manualNotice;if(same){if(job.status==ReceiptPrintController.Status.FAILED||job.status==ReceiptPrintController.Status.CANCELLED)notice+=" 打印未完成，仅可明确补打。";else if(job.status==ReceiptPrintController.Status.SIMULATED)notice+=" TEST模拟打印完成，未连接真实设备。";else if(job.status==ReceiptPrintController.Status.ACCEPTED)notice+=" 打印请求已受理，实际出纸未核实。";}
            if(state.selected!=null&&state.selected.swimTest==RaceRoster.SwimTest.UNKNOWN)notice+=" 深水测试状态待确认，请核对名单；提示不进入纸面。";
            if(Adapters.mode()==Verification.Mode.TEST)notice="TEST虚构测试（非有效核验） · "+notice;
            boolean reprint=printReady&&state.selected!=null&&state.selected.completed&&state.checkIn!=CheckIn.State.UNLINKABLE;
            passportPermit.update("passport/"+(state.selected==null?"":state.selected.token+"/"+state.selected.batchId+"/"+state.selected.recordId)+"/"+manualQueryEpoch.get()+"/"+manualSelectionEpoch+"/"+(same?job.jobId+"/"+job.status:"IDLE")+"/"+runtime.receiptTemplate().hashCode()+"/"+System.identityHashCode(sink),reprint&&sink!=null&&!routeBlocked&&page==Page.PASSPORT);
            return new PassportCheckView.Model(state,idle&&state.selected!=null&&!state.selected.completed,reprint,!manualBusy.get(),same?job.status:ReceiptPrintController.Status.IDLE,same?job.failure:ReceiptPrintController.Failure.NONE,notice,same?job.raster:null,passportPermit.token(),passportPermit.revision());
        }
        void openReceiptTemplate(){worker.execute(()->{
            if(page!=Page.SETTINGS||templates==null||templateSaving.get()||!canChangePrintMode())return;
            if(intake!=null)intake.pause();if(autoPrint!=null)autoPrint.invalidate();
            page=Page.TEMPLATE;templateState=templates.readFailed()?ReceiptTemplateSettingsView.State.FAILED:ReceiptTemplateSettingsView.State.READY;templateRevision++;publishedKey=null;publish();
        });}
        void leaveReceiptTemplate(){worker.execute(()->{if(page!=Page.TEMPLATE||templateSaving.get())return;page=Page.SETTINGS;templateRevision++;publishedKey=null;publish();});}
        void saveReceiptTemplate(ReceiptTemplate draft){
            if(!templateSaving.compareAndSet(false,true))return;
            worker.execute(()->{try{
                if(page!=Page.TEMPLATE||templates==null||draft==null||!canChangePrintMode()){
                    templateState=ReceiptTemplateSettingsView.State.FAILED;templateNotice="当前状态无法保存模板；原已保存模板不变。";return;}
                templateState=ReceiptTemplateSettingsView.State.SAVING;templateRevision++;publishedKey=null;publish();
                boolean saved=templates.save(draft);
                if(saved){runtime.setReceiptTemplate(templates.confirmed());receiptNames=new ReceiptNameSetting(templateNameStore);nameStatus=ReceiptNameControl.Status.READY;nameRevision++;}
                templateState=saved?ReceiptTemplateSettingsView.State.SAVED:ReceiptTemplateSettingsView.State.FAILED;
                templateNotice=saved?"小票模板已原子保存；重新打开和重启后保留。":"小票模板未保存，保留上次已保存模板和当前草稿，请重试。";
            }finally{templateSaving.set(false);templateRevision++;publishedKey=null;publish();}});
        }
        private ReceiptNameControl.Model receiptName(){
            String notice=nameStatus==ReceiptNameControl.Status.FAILED?"名称未保存；请核对40字符上限、私有存储及当前核验/打印状态。":"只修改小票顶部赛事名称；留空沿用名单赛事名，原导入信息不变。";
            return new ReceiptNameControl.Model(receiptNames==null?"":receiptNames.override(),nameStatus,40,!nameSaving.get()&&!modeSaving.get()&&canChangePrintMode()&&nameStatus!=ReceiptNameControl.Status.LOADING,notice);
        }
        void changePrintMode(PrintModeControl.Mode requested){
            if(requested==null||!modeSaving.compareAndSet(false,true))return;
            worker.execute(()->{
                try{
                    if(!canChangePrintMode()||!autoPrint.printingAllowed()||printSwitchSaving.get())return;
                    AutoPrintPolicy.Mode selected=requested==PrintModeControl.Mode.AUTO?AutoPrintPolicy.Mode.AUTOMATIC:AutoPrintPolicy.Mode.MANUAL;
                    if(autoPrint.mode()==selected)return;
                    requestedMode=requested;modeStatus=PrintModeControl.Status.SAVING;modeRevision++;publish();
                    boolean saved=autoPrint.setMode(selected);
                    requestedMode=null;modeStatus=saved?PrintModeControl.Status.READY:PrintModeControl.Status.FAILED;
                    if(!saved)printSwitchStatus=PrintModeControl.Status.FAILED;
                }finally{modeSaving.set(false);modeRevision++;publishedKey=null;publish();}
            });
        }
        /** Available during verification and in-flight sends; it never cancels verification or a device job. */
        void changePrintEnabled(boolean requested){
            AutoPrintPolicy policy=autoPrint;
            if(shuttingDown||policy==null||!printSwitchSaving.compareAndSet(false,true))return;
            policy.inhibitPrinting();
            worker.execute(()->{try{
                requestedPrintEnabled=requested;printSwitchStatus=PrintModeControl.Status.SAVING;modeRevision++;publish();
                boolean saved=policy.setEnabled(requested);
                requestedPrintEnabled=null;printSwitchStatus=saved?PrintModeControl.Status.READY:PrintModeControl.Status.FAILED;
                if(saved&&requested)modeStatus=PrintModeControl.Status.READY;
            }finally{printSwitchSaving.set(false);modeRevision++;publishedKey=null;publish();}});
        }
        private PrintModeControl.Model printMode(){
            PrintModeControl.Mode confirmed=autoPrint!=null&&autoPrint.mode()==AutoPrintPolicy.Mode.AUTOMATIC?PrintModeControl.Mode.AUTO:PrintModeControl.Mode.MANUAL;
            boolean allowed=autoPrint!=null&&autoPrint.printingAllowed();
            boolean enabled=allowed&&!printSwitchSaving.get()&&!modeSaving.get()&&!nameSaving.get()&&canChangePrintMode()&&modeStatus!=PrintModeControl.Status.LOADING;
            String notice=modeStatus==PrintModeControl.Status.FAILED?"模式保存失败，仍按上次确认设置；请重试。":!enabled&&modeStatus==PrintModeControl.Status.READY?"请完成当前核验或打印后切换模式。":Adapters.mode()==Verification.Mode.TEST?"虚构测试：仅模拟打印，设置不会连接真实打印机。":"自动仅新核验通过并保存后提交一次；实际出纸待确认。";
            if(printSwitchStatus==PrintModeControl.Status.FAILED)notice="打印设置读取或保存失败，已阻止新打印；请重试开关。";
            else notice="只阻止新提交；已交设备的任务可能继续出纸。重新开启不补印旧结果。"+(Adapters.mode()==Verification.Mode.TEST?" TEST仅模拟。":"");
            boolean toggle=!shuttingDown&&autoPrint!=null&&!printSwitchSaving.get()&&!modeSaving.get()&&printSwitchStatus!=PrintModeControl.Status.LOADING;
            return new PrintModeControl.Model(confirmed,requestedMode,modeStatus,enabled,notice,
                autoPrint!=null&&autoPrint.enabled(),requestedPrintEnabled,printSwitchStatus,toggle,allowed);
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
                readRecordsPage(0);
            });
        }
        private void readRecordsPage(int requested){
            recordsLoading=true;recordsError=null;selectedSessionId=null;recordsRevision++;publish();
            try{LocalRecordDatabase.Page slice=database.page(Adapters.mode(),filter.name(),requested);records=slice.rows;recordsPage=slice.page;recordsPageCount=slice.pageCount;recordsTotal=slice.total;}
            catch(Exception ex){records=Collections.emptyList();recordsPage=recordsPageCount=recordsTotal=0;recordsError="读取失败。请返回后重新进入；现有日志未清理。";}
            recordsLoading=false;recordsRevision++;publish();
        }
        void recordsPage(int requested){worker.execute(()->{if(page!=Page.RECORDS||recordsLoading||requested<0||requested>=recordsPageCount)return;readRecordsPage(requested);});}
        void openSettings() {
            if(faceSession.busy())return;
            worker.execute(() -> {
                if (page != Page.MAIN || settingsModel == null || faceSession.busy()
                        || !SettingsPresenter.canOpen(flow == null ? null : flow.snapshot())) return;
                operationModes.invalidateOperations();clearScene();
                clearFaceSurface();faceSession.invalidate();if(nextDayModule!=null)nextDayModule.cancel(faceSession.operationId());
                refreshStorageStatus();page = Page.SETTINGS; publishedKey = null; publish();
            });
        }
        private void refreshStorageStatus(){
            if(settingsModel==null)return;String status;
            try{int[] counts=database.counts();status="自动 "+counts[0]+" / "+LocalRecordDatabase.AUTO_LIMIT+"；人工 "+counts[1]+" / "+LocalRecordDatabase.MANUAL_LIMIT+"；打印保留 "+counts[2]+" / "+LocalRecordDatabase.CLAIM_LIMIT+"。容量满或保存失败会阻止新打印。";}
            catch(Exception error){status="存储读取失败；核验与打印必须等待成功保存，日志未清理。";}
            SettingsView.Model old=settingsModel;settingsModel=new SettingsView.Model(old.simulation,old.cardReader,old.camera,old.algorithm,old.authorization,status,old.packageName,old.versionName,old.versionCode);
        }
        void showMain() {
            worker.execute(() -> { page = Page.MAIN; publishedKey = null; publish(); });
        }
        void openDeviceConfig(){worker.execute(()->{if(page!=Page.SETTINGS||!canChangePrintMode())return;page=Page.DEVICE_CONFIG;if(deviceConfig!=null){deviceConfig.reload();configSnapshot=deviceConfig.snapshot();}publishedKey=null;publish();});}
        void changeConfigDraft(DeviceConfigDraft value){worker.execute(()->{if(page!=Page.DEVICE_CONFIG||deviceConfig==null||configSaving.get()||value==null)return;deviceConfig.edit(value);configSnapshot=deviceConfig.snapshot();publishedKey=null;publish();});}
        void saveConfig(String token,long revision){if(!configSaving.compareAndSet(false,true))return;worker.execute(()->{try{if(page!=Page.DEVICE_CONFIG||deviceConfig==null||!canChangePrintMode())return;publishedKey=null;publish();deviceConfig.save(token,revision);configSnapshot=deviceConfig.snapshot();}finally{configSaving.set(false);publishedKey=null;publish();}});}
        void rollbackConfig(long revision){if(!configSaving.compareAndSet(false,true))return;worker.execute(()->{try{if(page!=Page.DEVICE_CONFIG||deviceConfig==null||!canChangePrintMode())return;publishedKey=null;publish();deviceConfig.rollback(revision);configSnapshot=deviceConfig.snapshot();}finally{configSaving.set(false);publishedKey=null;publish();}});}
        void leaveDeviceConfig(){worker.execute(()->{if(page!=Page.DEVICE_CONFIG||configSaving.get())return;refreshStorageStatus();page=Page.SETTINGS;publishedKey=null;publish();});}
        private DeviceConfigView.Model configModel(){
            boolean busy=configSaving.get();DeviceConfigSession.Snapshot value=configSnapshot;
            boolean active=!shuttingDown&&sink!=null&&page==Page.DEVICE_CONFIG&&canChangePrintMode()&&!busy;
            return DeviceConfigPresenter.model(Adapters.mode()==Verification.Mode.TEST,value,configFailure,busy,active,runtime==null?"":runtime.startupNotice());
        }
        void openReceipt(){worker.execute(()->{
            if(!checkinAllowed()||page!=Page.MAIN||runtime==null||flow==null)return;
            if(ReceiptPresenter.current(flow,flow.snapshot().sessionId)==null)return;
            page=Page.RECEIPT;publishedKey=null;publish();
        });}
        void printReceipt(String id,boolean retry,String requestToken,long requestRevision,boolean confirmed){worker.execute(()->{boolean consumed=false;try{
            if(shuttingDown||sink==null||routeBlocked||templates==null||templates.readFailed()||templateSaving.get()||autoPrint==null||!autoPrint.printingAllowed()||page!=Page.RECEIPT||runtime==null||flow==null||!flow.snapshot().sessionId.equals(id))return;
            ReceiptPreviewView.Model model=ReceiptPresenter.model(flow,runtime.printing,runtime.printLayout,runtime.receiptHeading(),autoPrint.printingAllowed());
            if(retry?!model.retryEnabled:!model.printEnabled)return;
            boolean needs=retry||model.status!=ReceiptPrintController.Status.IDLE||flow.snapshot().checkIn==CheckIn.State.DUPLICATE;
            if(!receiptPermit.consume(requestToken,requestRevision,needs,confirmed))return;consumed=true;
            CheckInReceipt.Document document=ReceiptPresenter.current(flow,id,runtime.receiptHeading());
            if(document==null||runtime.printLayout==null||autoPrint==null||!autoPrint.beforeManualPrint(flow))return;
            // The existing reprint/retry UI asks the operator to assess possible duplicate output.
            // Automatic dispatch never enters this explicit renewal path.
            if(model.status!=ReceiptPrintController.Status.IDLE&&!runtime.printing.authorizeRetry(id,true))return;
            if(flow.snapshot().checkIn==CheckIn.State.DUPLICATE&&!runtime.printing.authorizeDuplicatePrint(id,confirmed))return;
            try{runtime.printing.submit(document,runtime.printLayout);}
            catch(IllegalStateException ex){/* A changed session is rejected by the controller; refresh from current state. */}
            }finally{if(consumed)receiptPermit.invalidate();publishedKey=null;publish();}
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
        void openRoster(){worker.execute(()->{
            if(page!=Page.MAIN||rosterImport==null||runtime==null||!SettingsPresenter.canOpen(flow==null?null:flow.snapshot())
                    ||runtime.printing.snapshot().status==ReceiptPrintController.Status.SENDING)return;
            // Explicit roster management ends only a saved terminal session, without changing auto-next.
            if(intake!=null)intake.pause();
            if(runtime.inbox!=null)runtime.inbox.clear();
            if(autoPrint!=null)autoPrint.invalidate();
            runtime.printing.cancel();
            if(flow.snapshot().stage==Verification.Stage.FINISHED)flow.next();
            page=Page.ROSTER;reloadRoster();rosterRevision++;publish();
        });}
        void beginChoice(String event,String group,Consumer<String> launch){beginChoice(event,group,ExcelRosterReader.WorksheetScope.ALL_WORKSHEETS,launch);}
        void beginChoice(String event,String group,ExcelRosterReader.WorksheetScope scope,Consumer<String> launch){Objects.requireNonNull(scope);worker.execute(()->{
            if(exportBusy.get()||page!=Page.ROSTER||!canReplaceRoster()||rosterState==RosterImportView.State.READING||rosterState==RosterImportView.State.SAVING)return;
            if(rosterImport.candidate()!=null)rosterImport.discard(rosterImport.candidate().id);
            importScope=scope;importEvent=event==null?"":event.trim();importGroup=group==null?"":group.trim();
            if(importEvent.length()>128||importGroup.length()>128){rosterState=RosterImportView.State.ERROR;rosterMessage="赛事名或默认组别过长，请缩短后重试。";rosterRevision++;publish();return;}
            pickToken=UUID.randomUUID().toString();rosterState=RosterImportView.State.READING;rosterMessage=rosterScopeNotice()+"请选择Excel文件。";rosterErrorPage=0;rosterRevision++;publish();launch.accept(pickToken);
        });}
        private String rosterScopeNotice(){return importScope==ExcelRosterReader.WorksheetScope.FIRST_WORKSHEET?"导入范围：仅第一个工作表。":"导入范围：全部工作表。";}
        void cancelChoice(String token){worker.execute(()->{if(token==null||!token.equals(pickToken))return;pickToken=null;rosterState=RosterImportView.State.IDLE;rosterMessage="文件选择已取消，当前名单保持不变。";rosterRevision++;publish();});}
        void importFile(String token,Uri uri){worker.execute(()->{
            if(token==null||!token.equals(pickToken)||page!=Page.ROSTER||!canReplaceRoster())return;
            pickToken=null;rosterMessage="正在读取并校验Excel名单。";rosterRevision++;publish();
            String name="";
            try(Cursor c=app.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}
            try(InputStream input=app.getContentResolver().openInputStream(uri)){rosterImport.preview(input,name,importEvent,importGroup,importScope);}
            catch(Exception ex){rosterImport.preview(null,"unreadable.xls",importEvent,importGroup,importScope);}
            ExcelRosterReader.Preview candidate=rosterImport.candidate();rosterState=candidate.roster==null?RosterImportView.State.INVALID:RosterImportView.State.REVIEW;
            rosterMessage=rosterScopeNotice()+(candidate.roster==null?"名单校验未通过；没有替换当前有效名单。":"所选范围全部行校验通过，请确认替换本场名单。");rosterRevision++;publish();
        });}
        void confirmRoster(String id){worker.execute(()->{
            if(exportBusy.get()||page!=Page.ROSTER||rosterImport==null)return;
            ExcelRosterReader.Preview candidate=rosterImport.candidate();if(candidate==null||!candidate.id.equals(id)||candidate.roster==null||!canReplaceRoster())return;
            rosterState=RosterImportView.State.SAVING;rosterMessage="正在保存本场名单。";rosterRevision++;publish();
            try{currentRoster=rosterImport.confirm(id,true);rosterCorrupt=false;rosterState=RosterImportView.State.SAVED;rosterMessage="名单已导入，可返回开始本场核验。";}
            catch(Exception ex){reloadRoster();rosterState=RosterImportView.State.REVIEW;rosterMessage="保存失败；请检查私有存储并重试，当前有效名单以已保存版本为准。";}
            rosterRevision++;publish();
        });}
        void discardRoster(String id){worker.execute(()->{if(exportBusy.get()||page!=Page.ROSTER||rosterImport==null)return;ExcelRosterReader.Preview p=rosterImport.candidate();if(p==null||!p.id.equals(id))return;rosterImport.discard(id);rosterState=RosterImportView.State.IDLE;rosterMessage="候选已丢弃，当前有效名单保持不变。";rosterErrorPage=0;rosterRevision++;publish();});}
        void rosterErrorPage(String id,int pageNumber){worker.execute(()->{ExcelRosterReader.Preview p=rosterImport==null?null:rosterImport.candidate();if(page!=Page.ROSTER||p==null||!p.id.equals(id))return;rosterErrorPage=pageNumber;rosterRevision++;publish();});}
        void leaveRoster(){worker.execute(()->{if(page!=Page.ROSTER)return;pickToken=null;if(rosterImport!=null&&rosterImport.candidate()!=null)rosterImport.discard(rosterImport.candidate().id);rosterState=RosterImportView.State.IDLE;rosterErrorPage=0;page=Page.MAIN;rosterRevision++;publish();});}
        private boolean canExportRoster(){return !shuttingDown&&page==Page.ROSTER&&currentRoster!=null&&!rosterCorrupt&&store!=null&&canReplaceRoster()
                &&rosterState!=RosterImportView.State.READING&&rosterState!=RosterImportView.State.SAVING&&pickToken==null;}
        private java.util.List<com.triathlon.prep.core.ManualRecord> manualHistoryUnavailable()throws java.io.IOException{throw new java.io.IOException("Manual history unavailable");}
        void beginRosterExport(java.util.function.BiConsumer<String,String> launch){
            if(shuttingDown||launch==null||!exportBusy.compareAndSet(false,true))return;
            worker.execute(()->{try{
                if(!canExportRoster())throw new IllegalStateException("Export not available");
                exportState=RosterImportView.ExportState.PREPARING;exportNotice="正在固定本机 / 当前名单批次快照。";rosterRevision++;publish();
                LocalRecordDatabase.History history=database.exportHistory(currentRoster,Adapters.mode());
                com.triathlon.prep.core.RosterStatusExport.Snapshot snapshot=com.triathlon.prep.core.RosterStatusExport.captureResolved(currentRoster,history.automatic,history.manual,Adapters.mode(),System.currentTimeMillis(),history.missing,history.total,LocalRecordDatabase.AUTO_LIMIT,LocalRecordDatabase.MANUAL_LIMIT,history.registered);
                ExportJob job=new ExportJob(snapshot);exportJob=job;exportState=RosterImportView.ExportState.CHOOSING;exportNotice="请选择保存Excel位置。"+snapshot.summary();rosterRevision++;publish();
                String name=(snapshot.mode==Verification.Mode.TEST?"TEST-":"LIVE-")+"本机名单核验状态-"+java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss",java.util.Locale.ROOT).format(java.time.Instant.ofEpochMilli(snapshot.capturedAt).atOffset(java.time.ZoneOffset.ofHours(8)))+".xls";
                launch.accept(job.id,name);
            }catch(Exception|LinkageError|OutOfMemoryError ex){exportJob=null;exportBusy.set(false);exportState=RosterImportView.ExportState.FAILED;exportNotice="无法导出：请确认本机已保存的有效名单及日志可读取；未生成核验状态文件。";rosterRevision++;publishedKey=null;publish();}});
        }
        void failRosterExportChoice(String token){worker.execute(()->{ExportJob job=exportJob;if(job==null||!job.id.equals(token)||exportState!=RosterImportView.ExportState.CHOOSING)return;
            job.abort();exportJob=null;exportBusy.set(false);exportState=RosterImportView.ExportState.FAILED;exportNotice="保存文件选择器不可用，未导出；本机名单和核验保持不变。";rosterRevision++;publishedKey=null;publish();});}
        void cancelRosterExport(String token){worker.execute(()->{ExportJob job=exportJob;if(job==null||!job.id.equals(token)||exportState!=RosterImportView.ExportState.CHOOSING)return;
            job.abort();exportJob=null;exportBusy.set(false);exportState=RosterImportView.ExportState.CANCELLED;exportNotice="已取消导出；本机名单和核验保持不变。";rosterRevision++;publishedKey=null;publish();});}
        void writeRosterExport(String token,android.net.Uri uri){worker.execute(()->{
            ExportJob job=exportJob;if(shuttingDown||job==null||!job.id.equals(token)||exportState!=RosterImportView.ExportState.CHOOSING||uri==null)return;
            exportState=RosterImportView.ExportState.WRITING;exportNotice="正在写入本机 / 当前名单批次Excel；尚未确认保存成功。";rosterRevision++;publish();
            exportIo.execute(()->{
                boolean saved=false;
                try{if(job.aborted.get())throw new java.io.IOException("Export cancelled");
                    try(java.io.OutputStream output=app.getContentResolver().openOutputStream(uri,"wt")){
                        if(output==null)throw new java.io.IOException("Output unavailable");job.stream=output;
                        java.io.OutputStream guarded=new java.io.FilterOutputStream(output){
                            public void write(int value)throws java.io.IOException{if(job.aborted.get())throw new java.io.IOException("Export cancelled");out.write(value);}
                            public void write(byte[] data,int offset,int length)throws java.io.IOException{if(job.aborted.get())throw new java.io.IOException("Export cancelled");out.write(data,offset,length);}
                        };
                        com.triathlon.prep.core.RosterStatusExport.writeXls(job.snapshot,guarded);output.flush();
                    }
                    saved=!job.aborted.get();
                }catch(Exception|LinkageError|OutOfMemoryError ex){/* Filename/path/provider exception details never enter UI. */}
                finally{job.stream=null;}
                final boolean success=saved;
                try{worker.execute(()->{if(shuttingDown||exportJob!=job)return;exportJob=null;exportBusy.set(false);
                    exportState=success?RosterImportView.ExportState.SUCCEEDED:RosterImportView.ExportState.FAILED;
                    exportNotice=success?"Excel已写入并关闭成功。"+job.snapshot.summary():"保存失败，目标文件可能不完整；请另选位置重试。本机名单和核验未更改。";
                    rosterRevision++;publishedKey=null;publish();});}catch(java.util.concurrent.RejectedExecutionException ignored){/* Closed Host never receives a false success. */}
            });
        });}
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
                filter = current; readRecordsPage(0);
            });
        }
        void select(String id) {
            worker.execute(() -> {
                if (page != Page.RECORDS || recordsLoading) return;
                selectedSessionId = id; recordsRevision++; publish();
            });
        }
        void act(VerificationView.PrimaryAction action, boolean cancel) {
            if(checkinAllowed()&&sink!=null&&page==Page.MAIN&&!routeBlocked){if(cancel)operationModes.invalidateOperations();if(cancel||action==VerificationView.PrimaryAction.BEGIN||action==VerificationView.PrimaryAction.RETAKE||action==VerificationView.PrimaryAction.NEXT)clearScene();}
            worker.execute(() -> {
                if (!checkinAllowed()||flow == null || page != Page.MAIN || sink==null) return;
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
            if(enrollmentHook!=null&&flow!=null){Verification.Snapshot current=flow.snapshot();if(current.scoresOnly)enrollmentHook.clear();else enrollmentHook.update(current);}
            refreshFaceBackend();
            if(sceneSlot!=null){sceneSlot.expire();if(flow!=null){Verification.Snapshot sceneState=flow.snapshot();if(sceneState.stage==Verification.Stage.IDLE||sceneState.stage==Verification.Stage.FINISHED&&!com.triathlon.prep.core.CheckIn.trustedSuccess(sceneState.result))clearScene();}}
            if(checkinAllowed()&&sink!=null&&autoPrint!=null&&runtime!=null&&flow!=null&&templates!=null&&!templates.readFailed()&&!templateSaving.get()&&!modeSaving.get()&&!nameSaving.get())autoPrint.tryAutomaticPrint(flow,runtime.printing,runtime.printLayout);
            syncIntake();
            // All policy locks/preferences stay on the worker; the UI receives only immutable snapshots.
            modeModel=printMode();
            nameModel=receiptName();
            if(page==Page.PASSPORT)passportModel=passportModel();else passportPermit.invalidate();
            templateModel=new ReceiptTemplateSettingsView.Model(templates==null?ReceiptTemplate.defaults():templates.confirmed(),templateState,templateNotice,templateRevision,
                    templates!=null&&!templateSaving.get()&&canChangePrintMode(),!templateSaving.get());
            String displayedExportNotice=exportState==RosterImportView.ExportState.IDLE&&currentRoster!=null&&!rosterCorrupt
                    ?"本机 / 当前名单批次；仅计已保存的完成核验。自动/人工日志独立上限各120000条，保存失败不计完成；旧记录无法关联时标待核对。":exportNotice;
            rosterExportModel=new RosterImportView.ExportModel(exportState,!exportBusy.get()&&canExportRoster(),displayedExportNotice);
            Verification.Snapshot state = flow == null ? null : flow.snapshot();
            if(speech!=null)speech.observe(SpeechPolicy.Frame.from(checkinAllowed()?state:null,checkinAllowed()&&sink!=null&&page==Page.MAIN&&!routeBlocked,waitingForCard()));
            SpeechControl.Model voiceModel=speechModel();
            ReceiptPrintController.Snapshot print=runtime==null?null:runtime.printing.snapshot();
            String key = (state == null ? "initializing" : state.sessionId + "/" + state.stage + "/" + state.saveState)
                    + "/" + problem + "/" + page + "/" + recordsRevision + "/" + diagnosticsRevision+"/"+rosterRevision+"/"+modeRevision+"/"+nameRevision+"/"+speechRevision+"/"+templateRevision+"/"+manualRevision+"/"+operationModes.snapshot().revision+"/"+faceSession.revision()+"/"+(print==null?"":print.jobId+"/"+print.status+"/"+print.failure);
            if (key.equals(publishedKey)) return;
            publishedKey = key;
            RecordsView.Model recordsModel = page == Page.RECORDS ? RecordsPresenter.model(records,
                    Adapters.mode(), filter, selectedSessionId, recordsLoading, recordsError) : null;
            RecordPageSnapshot recordPage=new RecordPageSnapshot(recordsPage,recordsPageCount,recordsTotal);
            DeviceConfigView.Model preparedConfig=configModel();
            Page currentPage = page;
            DiagnosticsView.Model currentDiagnostics = diagnosticsModel;
            boolean canOpen = (faceAllowed()||SettingsPresenter.canOpen(state))&&!faceSession.busy();
            VerificationModeControl.Model preparedOperationMode=operationModeModel();
            NextDayFaceView.Model preparedFace=faceSession.model(currentRoster,faceAllowed());
            FaceLibraryManagementView.Model preparedLibrary=page==Page.LIBRARY?libraryModel():null;
            long preparedModeRevision=operationModes.snapshot().revision;
            RosterImportView.Model rosterModel=page==Page.ROSTER?RosterImportPresenter.model(Adapters.mode()==Verification.Mode.TEST,rosterState,currentRoster,rosterCorrupt,
                    rosterImport==null?null:rosterImport.candidate(),importEvent,importGroup,rosterErrorPage,
                    canReplaceRoster()?rosterMessage:"请先返回完成本轮并进入下一位，再替换名单。",canReplaceRoster()):null;
            AutomaticIntakeControl.Model inputModel=intakeModel();
            Sink expected=sink;
            ReceiptPreviewView.Model preparedReceipt=page==Page.RECEIPT&&flow!=null&&runtime!=null&&templates!=null
                    ?ReceiptPresenter.graphicModel(flow,runtime.printing,runtime.printLayout,templates.confirmed(),receiptRenderer,
                    autoPrint!=null&&autoPrint.printingAllowed()&&!templates.readFailed()&&!templateSaving.get()):null;
            if(preparedReceipt!=null)preparedReceipt=receiptAuthority(preparedReceipt);else receiptPermit.invalidate();
            ReceiptPreviewView.Model navigationReceipt=preparedReceipt!=null?preparedReceipt:flow==null||runtime==null?null:ReceiptPresenter.model(flow,runtime.printing,runtime.printLayout,runtime.receiptHeading(),autoPrint!=null&&autoPrint.printingAllowed());
            main.post(() -> {
                if(sink==null || sink!=expected||currentPage!=page||preparedModeRevision!=operationModes.snapshot().revision)return;
                // Re-read the synchronized core here: an older queued IDLE render must not erase newly accepted images.
                Verification.Snapshot latest=flow==null?null:flow.snapshot();
                VerificationView.Model latestModel=VerificationPresenter.model(latest,Adapters.mode(),problem,runtime!=null && runtime.canBegin()&&!modeSaving.get()&&!nameSaving.get(),runtime==null?null:runtime.failureDiagnostic(latest));
                ReceiptPreviewView.Model receipt=navigationReceipt;
                if(intake!=null)latestModel=automaticModel(latestModel,latest);
                sink.show(latestModel,recordsModel,settingsModel,currentDiagnostics,currentPage,canOpen,latest,runtime==null?null:runtime.display,rosterModel,receipt,modeModel,nameModel,inputModel,voiceModel,recordPage,preparedConfig,preparedOperationMode,preparedFace,preparedLibrary);
            });
        }
        private ReceiptPreviewView.Model receiptAuthority(ReceiptPreviewView.Model base){
            Verification.Snapshot state=flow.snapshot();boolean needs=base.status!=ReceiptPrintController.Status.IDLE||state.checkIn==CheckIn.State.DUPLICATE;
            String key="receipt/"+state.mode+"/"+state.rosterBatchId+"/"+(state.athlete==null?"":state.athlete.bib)+"/"+base.sessionId+"/"+base.jobId+"/"+base.status+"/"+base.failure+"/"+state.checkIn+"/"+runtime.receiptTemplate().hashCode()+"/"+runtime.receiptHeading()+"/"+System.identityHashCode(sink);
            receiptPermit.update(key,sink!=null&&!routeBlocked&&page==Page.RECEIPT&&autoPrint!=null&&autoPrint.printingAllowed()&&(base.printEnabled||base.retryEnabled)&&base.raster!=null);
            return new ReceiptPreviewView.Model(base.sessionId,base.simulation,base.document,base.status,base.failure,base.jobId,base.notice,base.printEnabled,base.retryEnabled,base.cancelEnabled,base.backEnabled,base.raster,receiptPermit.token(),receiptPermit.revision(),needs);
        }
        private VerificationView.Model automaticModel(VerificationView.Model old,Verification.Snapshot state){
            VerificationView.PrimaryAction action=state!=null&&state.saveState==Verification.SaveState.FAILED?VerificationView.PrimaryAction.RETRY_SAVE:
                Adapters.mode()==Verification.Mode.TEST&&waitingForCard()?VerificationView.PrimaryAction.BEGIN:VerificationView.PrimaryAction.NONE;
            boolean standby=state!=null&&state.stage==Verification.Stage.IDLE;
            return new VerificationView.Model(old.simulation,old.step,standby?"自动待机接卡":old.title,
                standby?(Adapters.mode()==Verification.Mode.TEST?"等待虚构测试卡输入；模拟刷卡后自动核验，无卡不创建记录。":"请将身份证放在读卡区；刷卡后自动核验，无卡不创建记录。"):old.message,
                standby?"刷卡后自动核验":old.instruction,old.name,old.maskedNumber,old.score,old.liveness,old.saveStatus,
                old.certificatePhoto,old.livePhoto,action,action!=VerificationView.PrimaryAction.NONE,old.cancelEnabled,old.tone,old.rosterInfo,old.scoresOnly);
        }
        synchronized void close() {
            if(shuttingDown)return;
            shuttingDown=true;receiptPermit.invalidate();passportPermit.invalidate();
            operationModes.invalidateOperations();clearScene();
            clearFaceSurface();
            cancelLibraryWork();
            if(autoPrint!=null)autoPrint.inhibitPrinting();
            ExportJob endingExport=exportJob;if(endingExport!=null)endingExport.aborted.set(true);exportIo.shutdownNow();
            cameraPermissionEligible=false;
            sink = null;
            if(speech!=null)speech.cancel();
            if(intake!=null)intake.pause();
            // Runs after any in-flight initialization, including owners created after close() began.
            worker.execute(() -> {
                if(endingExport!=null)endingExport.abort();
                if(nextDayModule!=null)try{nextDayModule.close();nextDayModule=null;}catch(java.io.IOException release){problem="刷脸资源释放未确认。";}
                try{if(intake!=null)intake.close();}
                finally{try{if(speech!=null)speech.close();else if(speechPort!=null)speechPort.close();}
                    finally{try{if(runtime!=null)runtime.close();else if(flow!=null)flow.cancel();}finally{if(database!=null)database.close();}}}
            });
            worker.shutdown();
        }
    }
}









