package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Controlled setting display only. Host persists mode and supplies confirmed snapshots. */
public final class PrintModeControl extends LinearLayout {
    public enum Mode { MANUAL, AUTO }
    public enum Status { LOADING, READY, SAVING, FAILED }
    public interface Actions {
        void onModeChangeRequested(Mode requestedMode);
        default void onEnabledChangeRequested(boolean enabled) {}
    }
    public static final class Model {
        public final Mode confirmedMode, requestedMode;
        public final Status status;
        public final boolean changeEnabled;
        public final String notice;
        public final boolean confirmedEnabled,toggleEnabled,printingAllowed;
        public final Boolean requestedEnabled;
        public final Status enabledStatus;
        public Model(Mode confirmedMode,Mode requestedMode,Status status,boolean changeEnabled,String notice){
            this(confirmedMode,requestedMode,status,changeEnabled,notice,true,null,Status.READY,changeEnabled,true);
        }
        public Model(Mode confirmedMode,Mode requestedMode,Status status,boolean changeEnabled,String notice,
                boolean confirmedEnabled,Boolean requestedEnabled,Status enabledStatus,boolean toggleEnabled,boolean printingAllowed){
            if(confirmedMode==null||status==null)throw new IllegalArgumentException("mode/status required");
            if(status==Status.SAVING&&(requestedMode==null||requestedMode==confirmedMode))throw new IllegalArgumentException("new requested mode required");
            if((status==Status.LOADING||status==Status.SAVING)&&changeEnabled)throw new IllegalArgumentException("pending setting cannot change");
            if(status==Status.READY&&requestedMode!=null)throw new IllegalArgumentException("ready mode must be confirmed");
            if(status==Status.LOADING&&confirmedMode!=Mode.MANUAL)throw new IllegalArgumentException("unknown setting displays manual default");
            if(enabledStatus==null)throw new IllegalArgumentException("print enabled status required");
            if(enabledStatus==Status.SAVING&&requestedEnabled==null)throw new IllegalArgumentException("requested enabled required");
            if(enabledStatus!=Status.SAVING&&requestedEnabled!=null)throw new IllegalArgumentException("only saving has requested enabled");
            if((enabledStatus==Status.LOADING||enabledStatus==Status.SAVING)&&(toggleEnabled||printingAllowed))throw new IllegalArgumentException("pending enabled setting cannot change or print");
            if((enabledStatus==Status.FAILED||!confirmedEnabled)&&printingAllowed)throw new IllegalArgumentException("failed or disabled setting cannot print");
            this.confirmedMode=confirmedMode;this.requestedMode=requestedMode;this.status=status;this.changeEnabled=changeEnabled;this.notice=notice;
            this.confirmedEnabled=confirmedEnabled;this.requestedEnabled=requestedEnabled;this.enabledStatus=enabledStatus;
            this.toggleEnabled=toggleEnabled;this.printingAllowed=printingAllowed;
        }
    }
    private static final int BLUE=0xff1769b7,INK=0xff162638,MUTED=0xff637487,LINE=0xffdce4ec;
    private final Actions actions;
    private final Button manual,automatic,on,off;
    private final TextView state,enabledState;
    private Model model;
    private boolean togglePending;
    public PrintModeControl(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("actions required");this.actions=actions;
        setOrientation(VERTICAL);setPadding(dp(14),dp(12),dp(14),dp(12));setBackground(bg(Color.WHITE,LINE));
        addView(text("打印方式",17,INK,true));
        LinearLayout toggle=new LinearLayout(context);toggle.setOrientation(HORIZONTAL);toggle.setGravity(Gravity.CENTER_VERTICAL);
        on=button("开启打印");off=button("关闭打印");
        toggle.addView(on,new LayoutParams(0,dp(48),1));LayoutParams offParams=new LayoutParams(0,dp(48),1);offParams.leftMargin=dp(8);toggle.addView(off,offParams);add(toggle,10);
        enabledState=text("打印开关尚未读取，已阻止新打印",14,MUTED,false);add(enabledState,8);
        LinearLayout modes=new LinearLayout(context);modes.setOrientation(HORIZONTAL);modes.setGravity(Gravity.CENTER_VERTICAL);
        manual=button("手动打印");automatic=button("自动打印");
        modes.addView(manual,new LayoutParams(0,dp(48),1));LayoutParams second=new LayoutParams(0,dp(48),1);second.leftMargin=dp(8);modes.addView(automatic,second);add(modes,10);
        state=text("手动打印 · 默认值，设置尚未读取",14,MUTED,false);add(state,8);
        add(text("自动：仅本次核验通过并保存后打印一次；失败不自动重试。切换模式不会补打历史记录。",13,MUTED,false),8);
        manual.setOnClickListener(v->request(Mode.MANUAL));automatic.setOnClickListener(v->request(Mode.AUTO));
        on.setOnClickListener(v->requestEnabled(true));off.setOnClickListener(v->requestEnabled(false));
        render(new Model(Mode.MANUAL,null,Status.LOADING,false,null,true,null,Status.LOADING,false,false));
    }
    public void render(Model m){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("model required");Model previous=model;model=m;
        // Same-value recovery can reach READY without an intermediate SAVING frame.
        if(previous==null||m.enabledStatus!=Status.READY||previous.enabledStatus!=Status.READY
                ||m.confirmedEnabled!=previous.confirmedEnabled)togglePending=false;
        renderToggle(m);
        style(manual,m.confirmedMode==Mode.MANUAL,!togglePending&&m.changeEnabled&&m.printingAllowed&&m.confirmedMode!=Mode.MANUAL);
        style(automatic,m.confirmedMode==Mode.AUTO,!togglePending&&m.changeEnabled&&m.printingAllowed&&m.confirmedMode!=Mode.AUTO);
        String current=m.confirmedMode==Mode.MANUAL?"手动打印":"自动打印";
        String label;
        switch(m.status){case LOADING:label="手动打印 · 默认值，设置尚未读取";break;
            case SAVING:label="正在保存"+(m.requestedMode==Mode.MANUAL?"手动":"自动")+"模式；当前仍为"+current;break;
            case FAILED:label="设置未保存，仍为"+current;break;
            default:label="当前："+current+"（已确认）";break;}
        if(m.notice!=null&&!m.notice.trim().isEmpty())label+="\n"+m.notice;
        state.setText(label);state.setTextColor(m.status==Status.FAILED?0xffa13730:MUTED);
    }
    private void renderToggle(Model m){
        boolean retry=m.enabledStatus==Status.FAILED;
        style(on,m.confirmedEnabled,m.toggleEnabled&&!togglePending&&(!m.confirmedEnabled||retry));
        style(off,!m.confirmedEnabled,m.toggleEnabled&&!togglePending&&(m.confirmedEnabled||retry));
        on.setContentDescription("开启打印"+(m.confirmedEnabled?"，最近确认开启":"")+(retry?"，重试保存":""));
        off.setContentDescription("关闭打印"+(!m.confirmedEnabled?"，最近确认关闭":"")+(retry?"，重试保存":""));
        String label;switch(m.enabledStatus){
            case LOADING:label="打印开关尚未读取，已阻止新打印";break;
            case SAVING:label="正在保存打印"+(m.requestedEnabled?"开启":"关闭")+"；已阻止新打印，最近确认"+(m.confirmedEnabled?"开启":"关闭");break;
            case FAILED:label="打印设置读取/保存失败，已阻止新打印，请重试";break;
            default:label=m.printingAllowed?"打印已开启（已确认）":"打印"+(m.confirmedEnabled?"已开启（已确认），当前阻止新打印":"已关闭（已确认），已阻止新打印");break;
        }
        if(togglePending)label="打印开关设置正在提交；等待确认";
        enabledState.setText(label);enabledState.setTextColor(m.enabledStatus==Status.FAILED?0xffa13730:MUTED);
    }
    private void requestEnabled(boolean enabled){Model m=model;
        if(m==null||!m.toggleEnabled||togglePending||m.enabledStatus==Status.LOADING||m.enabledStatus==Status.SAVING
                ||(m.enabledStatus!=Status.FAILED&&m.confirmedEnabled==enabled))return;
        togglePending=true;on.setEnabled(false);off.setEnabled(false);manual.setEnabled(false);automatic.setEnabled(false);
        enabledState.setText("正在提交打印"+(enabled?"开启":"关闭")+"设置；等待确认");actions.onEnabledChangeRequested(enabled);
    }
    private void request(Mode requested){Model m=model;if(m!=null&&!togglePending&&m.printingAllowed&&m.changeEnabled&&m.status!=Status.LOADING&&m.status!=Status.SAVING&&m.confirmedMode!=requested)actions.onModeChangeRequested(requested);}
    private void style(Button b,boolean selected,boolean enabled){b.setSelected(selected);b.setEnabled(enabled);b.setAlpha(enabled||selected?1f:.45f);b.setTextColor(selected?Color.WHITE:BLUE);b.setBackground(bg(selected?BLUE:Color.WHITE,selected?BLUE:LINE));b.setContentDescription(b.getText()+ (selected?"，当前模式":"，切换模式"));}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(8));d.setStroke(dp(1),stroke);return d;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s){Button b=new Button(getContext());b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setMinWidth(0);b.setPadding(dp(6),0,dp(6),0);return b;}
    private void add(android.view.View v,int gap){LayoutParams p=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);p.topMargin=dp(gap);addView(v,p);}
}
