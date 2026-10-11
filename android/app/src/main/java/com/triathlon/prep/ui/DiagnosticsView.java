package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Displays supplied observations only. No permission, camera, device or storage calls. */
public final class DiagnosticsView extends ScrollView {
    public interface Actions { void onBack(); void onRefresh(); }
    public static final class Camera {
        public final String id, direction, yuvSizes, zsl;
        public Camera(String id,String direction,String yuvSizes,String zsl) {
            this.id=id;this.direction=direction;this.yuvSizes=yuvSizes;this.zsl=zsl;
        }
    }
    public static final class Model {
        public final boolean simulation, refreshing;
        public final String environment, systemVersion, brandModel, abi, appVersion, cameraQueryStatus;
        public final List<Camera> cameras;
        public Model(boolean simulation,boolean refreshing,String environment,String systemVersion,
                String brandModel,String abi,String appVersion,String cameraQueryStatus,List<Camera> cameras) {
            if(cameras==null)throw new IllegalArgumentException("cameras required; use empty list for unavailable");
            List<Camera> copy=new ArrayList<>(cameras);
            if(copy.contains(null))throw new IllegalArgumentException("null camera");
            this.simulation=simulation;this.refreshing=refreshing;this.environment=environment;
            this.systemVersion=systemVersion;this.brandModel=brandModel;this.abi=abi;
            this.appVersion=appVersion;this.cameraQueryStatus=cameraQueryStatus;
            this.cameras=Collections.unmodifiableList(copy);
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7,LINE=0xffdce4ec;
    private final TextView mode, environment, system, brand, abi, version, query;
    private final LinearLayout cameras;
    private final Button refresh;
    public DiagnosticsView(Context context,Actions actions) {
        super(context);if(actions==null)throw new IllegalArgumentException("actions required");
        setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));
        addView(panel,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        Button back=button("← 返回设置");back.setOnClickListener(v->actions.onBack());panel.addView(back);
        LinearLayout header=row();header.addView(text("设备诊断",25,INK,true),new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        mode=text("待初始化",13,0xff825409,true);mode.setPadding(dp(9),dp(7),dp(9),dp(7));mode.setBackground(bg(0xfffff0d6));header.addView(mode);add(panel,header,14);
        environment=text("运行环境未确认",15,MUTED,false);add(panel,environment,12);
        LinearLayout base=card("基础信息");system=field(base,"系统版本 / API");brand=field(base,"品牌 / 型号");abi=field(base,"支持 ABI");version=field(base,"应用版本");add(panel,base,18);
        LinearLayout camera=card("相机静态能力");query=text("尚未查询",15,MUTED,false);add(camera,query,10);
        cameras=column();add(camera,cameras,8);add(camera,text("仅查看系统能力声明，不打开相机。方向与尺寸不代表 RGB/IR 已可用。",14,MUTED,false),14);add(panel,camera,16);
        LinearLayout pending=card("待设备实测");
        add(pending,text("USB 读证、芯片照片：未实测\n在线服务配置与评分规则：待确认\n标准相机、画面方向与编码：未实测\n比对效果与活体防攻击：未实测",15,MUTED,false),12);
        add(pending,text("相机能力以系统声明为准；声明不等同实测。",14,MUTED,false),12);add(panel,pending,16);
        refresh=button("刷新只读信息");refresh.setOnClickListener(v->{if(refresh.isEnabled()){refresh.setEnabled(false);actions.onRefresh();}});add(panel,refresh,18);
        TextView foot=text("只读诊断 · 不采集、不激活、不修改核验记录\n本页不读取设备唯一标识",12,MUTED,false);foot.setGravity(Gravity.CENTER);add(panel,foot,16);
    }
    public void render(Model m) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("model required");
        mode.setText(m.simulation?"虚构测试包":"正式包 · 未启用");
        environment.setText("当前运行环境："+value(m.environment)+"\n本页信息不构成终端实机验证。");
        system.setText(value(m.systemVersion));brand.setText(value(m.brandModel));abi.setText(value(m.abi));version.setText(value(m.appVersion));
        query.setText(m.refreshing?"正在读取静态信息…":value(m.cameraQueryStatus));
        cameras.removeAllViews();
        if(m.cameras.isEmpty())add(cameras,text("暂无可读相机信息；以查询状态及原因说明为准。",15,MUTED,false),8);
        for(Camera c:m.cameras){add(cameras,text("相机 ID："+value(c.id),17,INK,true),12);add(cameras,text("方向："+value(c.direction)+"\nYUV 尺寸："+value(c.yuvSizes)+"\nZSL："+value(c.zsl),15,MUTED,false),8);}
        refresh.setEnabled(!m.refreshing);refresh.setText(m.refreshing?"正在读取…":"刷新只读信息");
    }
    private String value(String s){return s==null||s.trim().isEmpty()?"未知 / 未读取":s;}
    private TextView field(LinearLayout parent,String label){add(parent,text(label,13,MUTED,false),14);TextView t=text("未知 / 未读取",16,INK,false);add(parent,t,5);return t;}
    private LinearLayout card(String title){LinearLayout l=column();l.setPadding(dp(14),dp(16),dp(14),dp(16));l.setBackground(bg(Color.WHITE));l.addView(text(title,20,INK,true));return l;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);p.topMargin=dp(gap);parent.addView(child,p);}
    private GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));d.setStroke(dp(1),LINE);return d;}
    private Button button(String label){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(BLUE);b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(5),dp(12),dp(5));b.setBackground(bg(Color.WHITE));return b;}
}
