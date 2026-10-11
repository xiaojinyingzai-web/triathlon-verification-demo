package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Passive participant display. Host owns session, result and Presentation lifecycle. */
public final class AthleteView extends ScrollView {
    public enum Phase { WAITING_CARD, READING, ALIGN, PROCESSING, PASSED, REJECTED, NEED_STAFF, PAUSED }
    public static final class Model {
        public final boolean simulation;
        public final String sessionId,eventName,athleteName,bib,safeMessage;
        public final Phase phase;
        public Model(boolean simulation,String sessionId,Phase phase,String eventName,
                String athleteName,String bib,String safeMessage){
            if(phase==null)throw new IllegalArgumentException("phase required");
            if(sessionId!=null&&sessionId.trim().isEmpty())throw new IllegalArgumentException("empty session");
            if(sessionId==null&&phase!=Phase.WAITING_CARD&&phase!=Phase.PAUSED&&phase!=Phase.NEED_STAFF)
                throw new IllegalArgumentException("current session required");
            if(phase==Phase.WAITING_CARD&&sessionId!=null)throw new IllegalArgumentException("waiting must clear session");
            this.simulation=simulation;this.sessionId=sessionId;this.phase=phase;this.eventName=eventName;
            boolean identity=sessionId!=null&&phase!=Phase.READING&&phase!=Phase.PAUSED;
            this.athleteName=identity?athleteName:null;this.bib=identity?bib:null;this.safeMessage=safeMessage;
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7;
    private final TextView event,mode,symbol,prompt,person,message,next,foot;
    private final LinearLayout state;
    private String boundSession;
    public AthleteView(Context context){
        super(context);setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(28),dp(20),dp(28),dp(20));
        addView(panel,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        event=text("赛事身份核验",20,INK,true);header.addView(event,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        mode=text("",14,0xff825409,true);header.addView(mode);panel.addView(header);
        state=column();state.setGravity(Gravity.CENTER);state.setPadding(dp(24),dp(24),dp(24),dp(24));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,0,1);sp.topMargin=dp(18);panel.addView(state,sp);
        symbol=text("",48,BLUE,true);prompt=text("",34,INK,true);person=text("",22,INK,true);
        message=text("",20,MUTED,false);next=text("",18,MUTED,false);
        for(TextView v:new TextView[]{symbol,prompt,person,message,next}){v.setGravity(Gravity.CENTER);v.setLineSpacing(dp(3),1f);}
        state.addView(symbol);add(state,prompt,10);add(state,person,14);add(state,message,12);add(state,next,18);
        foot=text("",14,MUTED,false);foot.setGravity(Gravity.CENTER);add(panel,foot,16);
        clearDisplay();
    }
    public void bindSession(String sessionId){
        checkMain();if(sessionId!=null&&sessionId.trim().isEmpty())throw new IllegalArgumentException("empty session");
        if(equal(boundSession,sessionId))return;boundSession=sessionId;clearDisplay();
    }
    /** Host binds first. Old-session snapshots are rejected, including late results after clear. */
    public boolean render(Model m){
        checkMain();if(m==null)throw new IllegalArgumentException("model required");if(!equal(boundSession,m.sessionId))return false;
        event.setText(empty(m.eventName)?"赛事身份核验":m.eventName);mode.setText(m.simulation?"虚构测试 · 非身份核验":"身份核验");
        int color=BLUE,fill=0xffeef5fc;String sign="○",heading="请刷身份证",hint="请将身份证放在读卡区";
        switch(m.phase){
            case READING:heading="正在读取身份证";hint="请保持证件在读卡区，稍候";break;
            case ALIGN:heading="请正对摄像头";hint="保持面部清晰，系统将自动采集";break;
            case PROCESSING:heading="正在核验，请稍候";hint="系统正在处理本次核验";break;
            case PASSED:sign="✓";heading=m.simulation?"模拟核验通过":"核验通过";hint="请按工作人员指引继续办理";color=0xff236c47;fill=0xffedf7f1;break;
            case REJECTED:sign="!";heading=m.simulation?"模拟核验未通过":"核验未通过";hint="请联系现场工作人员";color=0xffa13730;fill=0xfffcf0ef;break;
            case NEED_STAFF:sign="!";heading="请联系工作人员";hint="工作人员将协助处理";color=0xff8b5b0f;fill=0xfffff7e9;break;
            case PAUSED:sign="—";heading="核验暂未就绪";hint="请稍候，或联系工作人员";color=0xff8b5b0f;fill=0xfffff7e9;break;
            default:break;
        }
        symbol.setText(sign);symbol.setTextColor(color);prompt.setText(heading);prompt.setTextColor(color);
        GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(16));state.setBackground(bg);
        person.setText((empty(m.athleteName)?"":m.athleteName)+(empty(m.bib)?"":"   比赛号码："+m.bib));
        person.setVisibility(empty(m.athleteName)&&empty(m.bib)?GONE:VISIBLE);
        message.setText(empty(m.safeMessage)?hint:m.safeMessage);next.setText(m.phase==Phase.WAITING_CARD?"刷卡后自动核验，无需点击":"");next.setVisibility(m.phase==Phase.WAITING_CARD?VISIBLE:GONE);
        foot.setText(m.simulation?"虚构资料与模拟结果，仅供流程演示":"仅显示本次核验，请遵循现场指引");return true;
    }
    private void clearDisplay(){event.setText("赛事身份核验");mode.setText("待同步");symbol.setText("—");prompt.setText("请稍候");person.setText("");person.setVisibility(GONE);message.setText("正在同步核验状态");next.setText("");foot.setText("仅显示当前核验状态");state.setBackgroundColor(0xffeef5fc);}
    @Override protected void onDetachedFromWindow(){bindSession(null);clearDisplay();super.onDetachedFromWindow();}
    private static boolean equal(String a,String b){return a==null?b==null:a.equals(b);}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static void checkMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI update on main thread");}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void add(LinearLayout p,TextView v,int gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);lp.topMargin=dp(gap);p.addView(v,lp);}
}
