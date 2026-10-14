package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Host supplies device status; optional receipt editor submits requests without persisting. */
public final class SettingsView extends ScrollView {
    public interface Actions { void onBack(); default void onOpenNextDayFace(){} default void onOpenFaceLibrary(){} }
    public static final class Model {
        public final boolean simulation;
        public final String cardReader, camera, algorithm, authorization, storage;
        public final String packageName, versionName;
        public final long versionCode;
        public Model(boolean simulation, String cardReader, String camera, String algorithm,
                String authorization, String storage, String packageName,
                String versionName, long versionCode) {
            if(versionCode<0)throw new IllegalArgumentException("negative version code");
            this.simulation=simulation;this.cardReader=cardReader;this.camera=camera;
            this.algorithm=algorithm;this.authorization=authorization;this.storage=storage;
            this.packageName=packageName;this.versionName=versionName;this.versionCode=versionCode;
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7,LINE=0xffdce4ec;
    private final TextView mode, packageName, versionName, versionCode, packageMode, foot;
    private final TextView[] statuses=new TextView[5];
    private final ReceiptNameControl receiptNameControl;
    private final DisplayRoleControl displayRoleControl;
    private final SpeechControl speechControl;
    private final Button receiptTemplateEntry;
    private Runnable onReceiptTemplateRequested;
    private final Button passportEntry;
    private Runnable onPassportCheckRequested;
    private Button deviceConfigEntry;
    private Runnable onDeviceConfigRequested;

    public SettingsView(Context context,Actions actions) {
        this(context,actions,null);
    }
    public SettingsView(Context context,Actions actions,Runnable onDiagnostics) {
        this(context,actions,onDiagnostics,null);
    }
    public SettingsView(Context context,Actions actions,Runnable onDiagnostics,ReceiptNameControl.Actions receiptNameActions) {
        this(context,actions,onDiagnostics,receiptNameActions,null);
    }
    public SettingsView(Context context,Actions actions,Runnable onDiagnostics,ReceiptNameControl.Actions receiptNameActions,DisplayRoleControl.Actions displayRoleActions) {
        this(context,actions,onDiagnostics,receiptNameActions,displayRoleActions,null);
    }
    public SettingsView(Context context,Actions actions,Runnable onDiagnostics,ReceiptNameControl.Actions receiptNameActions,DisplayRoleControl.Actions displayRoleActions,SpeechControl.Actions speechActions) {
        super(context);if(actions==null)throw new IllegalArgumentException("actions required");
        setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));
        addView(panel,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        Button back=button("← 返回核验",BLUE);back.setOnClickListener(v->actions.onBack());panel.addView(back);
        LinearLayout header=row();LinearLayout heading=column();heading.addView(text("设备与设置",25,INK,true));heading.addView(text("查看设备状态与应用版本",14,MUTED,false));header.addView(heading,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        mode=text("待初始化",13,0xff825409,true);mode.setPadding(dp(9),dp(7),dp(9),dp(7));mode.setBackground(background(0xfffff0d6,0xfffff0d6,8));header.addView(mode);add(panel,header,14);

        LinearLayout devices=card();devices.addView(text("设备状态",20,INK,true));
        String[] labels={"身份证读卡模块","现场摄像头","人证比对 / 活体","算法服务配置","本地存储"};
        for(int i=0;i<labels.length;i++)statuses[i]=field(devices,labels[i],"未核实");
        add(devices,text("连接设备后核对读证照片、标准相机与在线比对服务配置。",15,MUTED,false),14);add(panel,devices,20);
        deviceConfigEntry=button("设备配置准备",BLUE);deviceConfigEntry.setEnabled(false);deviceConfigEntry.setVisibility(android.view.View.GONE);
        deviceConfigEntry.setOnClickListener(v->{if(onDeviceConfigRequested!=null)onDeviceConfigRequested.run();});add(panel,deviceConfigEntry,12);
        Button nextDayEntry=button("刷脸模块状态与设置",BLUE);nextDayEntry.setOnClickListener(v->actions.onOpenNextDayFace());add(panel,nextDayEntry,12);
        Button faceLibraryEntry=button("人脸库管理",BLUE);faceLibraryEntry.setOnClickListener(v->actions.onOpenFaceLibrary());add(panel,faceLibraryEntry,12);
        receiptNameControl=receiptNameActions==null?null:new ReceiptNameControl(context,receiptNameActions);
        if(receiptNameControl!=null){LinearLayout receipt=card();receipt.addView(receiptNameControl);add(panel,receipt,16);}
        receiptTemplateEntry=button("小票模板设置",BLUE);receiptTemplateEntry.setEnabled(false);receiptTemplateEntry.setVisibility(android.view.View.GONE);
        receiptTemplateEntry.setOnClickListener(v->{if(onReceiptTemplateRequested!=null)onReceiptTemplateRequested.run();});add(panel,receiptTemplateEntry,12);
        passportEntry=button("护照人工核验",BLUE);passportEntry.setEnabled(false);passportEntry.setVisibility(android.view.View.GONE);passportEntry.setOnClickListener(v->{if(onPassportCheckRequested!=null)onPassportCheckRequested.run();});add(panel,passportEntry,12);
        displayRoleControl=displayRoleActions==null?null:new DisplayRoleControl(context,displayRoleActions);
        if(displayRoleControl!=null){LinearLayout displays=card();displays.addView(displayRoleControl);add(panel,displays,16);}
        speechControl=speechActions==null?null:new SpeechControl(context,speechActions);
        if(speechControl!=null){LinearLayout speech=card();speech.addView(speechControl);add(panel,speech,16);}

        LinearLayout threshold=card();threshold.addView(text("核验阈值",20,INK,true));add(threshold,text("在线服务评分规则待确认",14,MUTED,false),8);
        TextView thresholdLabel=text("人证比对阈值",16,INK,false);add(threshold,thresholdLabel,16);
        EditText input=new EditText(context);input.setText("待服务规则确认");input.setTextSize(16);input.setTextColor(0xff85929e);input.setEnabled(false);input.setSingleLine(true);input.setPadding(dp(12),dp(10),dp(12),dp(10));input.setBackground(background(0xfff1f4f7,LINE,7));input.setContentDescription("人证比对阈值，服务评分规则待确认，不可编辑");add(threshold,input,8);
        Button restore=button("恢复默认阈值",0xff85929e);restore.setEnabled(false);restore.setBackground(background(0xfff0f3f6,0xffe0e6eb,9));add(threshold,restore,12);
        add(threshold,text("在线服务评分规则与通过阈值确认后，才能配置使用。当前没有默认值。",15,MUTED,false),14);add(panel,threshold,16);

        LinearLayout version=card();version.addView(text("应用信息",20,INK,true));
        versionName=field(version,"版本名称","未读取");versionCode=field(version,"版本编号","未读取");packageMode=field(version,"包模式","未读取");
        add(version,text("应用包名",13,MUTED,false),14);packageName=text("未读取",14,INK,false);add(version,packageName,5);add(panel,version,16);
        Button diagnostics=button("设备诊断",BLUE);diagnostics.setEnabled(onDiagnostics!=null);
        diagnostics.setOnClickListener(v->{if(onDiagnostics!=null)onDiagnostics.run();});add(panel,diagnostics,16);
        foot=text("原生准备界面 · 未检查真实设备",12,MUTED,false);foot.setGravity(Gravity.CENTER);add(panel,foot,18);
    }

    public void render(Model m) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("model required");
        mode.setText(m.simulation?"虚构测试":"正式模式");
        if(speechControl!=null)speechControl.setSimulation(m.simulation);
        String[] values={m.cardReader,m.camera,m.algorithm,m.authorization,m.storage};
        for(int i=0;i<values.length;i++)statuses[i].setText(value(values[i],"未核实"));
        packageName.setText(value(m.packageName,"未读取"));versionName.setText(value(m.versionName,"未读取"));
        versionCode.setText(Long.toString(m.versionCode));
        packageMode.setText(m.simulation?"虚构测试包 · 非身份核验":"正式包 · 启用条件以配置为准");
        foot.setText(m.simulation?"虚构测试包 · 流程通过不代表设备或算法正常\n本页不修改核验记录":"设备与在线比对 · 实机验证待完成\n本页不修改核验记录");
    }
    public boolean renderReceiptName(ReceiptNameControl.Model m){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("name model required");if(receiptNameControl==null)return false;receiptNameControl.render(m);return true;
    }
    public boolean renderDisplayRoles(DisplayRoleControl.Model m){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("display model required");if(displayRoleControl==null)return false;displayRoleControl.render(m);return true;
    }
    public boolean renderSpeech(SpeechControl.Model m){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("speech model required");if(speechControl==null)return false;speechControl.render(m);return true;
    }
    public void setOnReceiptTemplateRequested(Runnable callback){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("template entry on main thread");
        onReceiptTemplateRequested=callback;receiptTemplateEntry.setEnabled(callback!=null);receiptTemplateEntry.setVisibility(callback==null?android.view.View.GONE:android.view.View.VISIBLE);
    }
    public void setOnPassportCheckRequested(Runnable callback){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("passport entry on main thread");onPassportCheckRequested=callback;passportEntry.setEnabled(callback!=null);passportEntry.setVisibility(callback==null?android.view.View.GONE:android.view.View.VISIBLE);
    }
    public void setOnDeviceConfigRequested(Runnable callback){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("config entry on main thread");
        onDeviceConfigRequested=callback;deviceConfigEntry.setEnabled(callback!=null);deviceConfigEntry.setVisibility(callback==null?android.view.View.GONE:android.view.View.VISIBLE);
    }
    private String value(String s,String fallback){return s==null||s.trim().isEmpty()?fallback:s;}
    private TextView field(LinearLayout parent,String label,String value){LinearLayout r=row();r.setMinimumHeight(dp(48));r.addView(text(label,16,INK,false),new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));TextView v=text(value,14,MUTED,false);v.setGravity(Gravity.END);r.addView(v,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));add(parent,r,6);return v;}
    private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(14),dp(16),dp(14),dp(16));l.setBackground(background(Color.WHITE,LINE,12));return l;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void add(LinearLayout parent,android.view.View child,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);p.topMargin=dp(gap);parent.addView(child,p);}
    private GradientDrawable background(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private Button button(String label,int color){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(color);b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(5),dp(12),dp(5));b.setBackground(background(Color.WHITE,LINE,9));return b;}
}
