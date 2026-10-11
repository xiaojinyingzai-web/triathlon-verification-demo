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
import com.triathlon.prep.core.CheckInReceipt;
import com.triathlon.prep.core.ReceiptPrintController;

/** Passive receipt display. Host owns eligibility, transport, jobs and document/session lifetime. */
public final class ReceiptPreviewView extends ScrollView {
    public interface Actions {
        void onPrintRequested(String sessionId);
        void onRetryRequested(String sessionId);
        void onCancelRequested(String sessionId, String jobId);
        void onBack(String sessionId);
    }
    public static final class Model {
        public final String sessionId, jobId, notice;
        public final boolean simulation, printEnabled, retryEnabled, cancelEnabled, backEnabled;
        public final CheckInReceipt.Document document;
        public final ReceiptPrintController.Status status;
        public final ReceiptPrintController.Failure failure;
        public Model(String sessionId, boolean simulation, CheckInReceipt.Document document,
                ReceiptPrintController.Status status, ReceiptPrintController.Failure failure,
                String jobId, String notice, boolean printEnabled, boolean retryEnabled,
                boolean cancelEnabled, boolean backEnabled) {
            if(empty(sessionId)||status==null||failure==null)throw new IllegalArgumentException("session/status/failure required");
            if(document!=null&&(!sessionId.equals(document.sessionId)||simulation!=document.simulation))
                throw new IllegalArgumentException("document session/mode mismatch");
            if((printEnabled||retryEnabled||cancelEnabled)&&document==null)
                throw new IllegalArgumentException("document required for actions");
            if(printEnabled&&((status!=ReceiptPrintController.Status.IDLE
                    &&status!=ReceiptPrintController.Status.SIMULATED
                    &&status!=ReceiptPrintController.Status.ACCEPTED)||retryEnabled))
                throw new IllegalArgumentException("invalid print state");
            if(retryEnabled&&status!=ReceiptPrintController.Status.FAILED&&status!=ReceiptPrintController.Status.CANCELLED)
                throw new IllegalArgumentException("invalid retry state");
            if(cancelEnabled&&(status!=ReceiptPrintController.Status.SENDING||empty(jobId)))
                throw new IllegalArgumentException("active job required for cancel");
            if(status==ReceiptPrintController.Status.SENDING&&(empty(jobId)||printEnabled||retryEnabled))
                throw new IllegalArgumentException("sending requires job and disables submissions");
            if((status==ReceiptPrintController.Status.SIMULATED&&!simulation)
                    ||(status==ReceiptPrintController.Status.ACCEPTED&&simulation))
                throw new IllegalArgumentException("completion mode mismatch");
            this.sessionId=sessionId;this.simulation=simulation;this.document=document;this.status=status;
            this.failure=failure;this.jobId=jobId;this.notice=notice;this.printEnabled=printEnabled;
            this.retryEnabled=retryEnabled;this.cancelEnabled=cancelEnabled;this.backEnabled=backEnabled;
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7,LINE=0xffdce4ec;
    private final Actions actions;
    private final TextView mode, banner, event, title, person, explanation, statusText, foot;
    private final LinearLayout paper, steps;
    private final Button back, print, retry, cancel;
    private String boundSession;
    private Model model;
    public ReceiptPreviewView(Context context, Actions actions) {
        super(context);if(actions==null)throw new IllegalArgumentException("actions required");this.actions=actions;
        setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));
        addView(panel,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        back=button("← 返回核验",false);panel.addView(back);
        LinearLayout header=row();header.addView(text("小票预览",25,INK,true),new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        mode=text("待初始化",13,0xff825409,true);mode.setPadding(dp(9),dp(7),dp(9),dp(7));mode.setBackground(background(0xfffff0d6,0xfffff0d6,8));header.addView(mode);add(panel,header,14);
        add(panel,text("58mm 内容预览 · 实际纸宽与打印效果待设备确认",14,MUTED,false),6);
        banner=text("",19,0xff825409,true);banner.setGravity(Gravity.CENTER);banner.setPadding(dp(12),dp(12),dp(12),dp(12));banner.setBackground(background(0xfffff0d6,0xffe5c78e,9));add(panel,banner,16);
        paper=column();paper.setPadding(dp(22),dp(20),dp(22),dp(20));paper.setBackground(background(Color.WHITE,LINE,12));
        event=text("",20,INK,true);event.setGravity(Gravity.CENTER);paper.addView(event);
        title=text("",23,INK,true);title.setGravity(Gravity.CENTER);add(paper,title,8);
        View divider=new View(context);divider.setBackgroundColor(LINE);add(paper,divider,16);divider.getLayoutParams().height=dp(1);
        person=text("",18,INK,false);person.setLineSpacing(dp(5),1f);add(paper,person,16);
        explanation=text("泳池测试：勾选表示需测试，不表示已完成。",15,0xff8b5b0f,true);add(paper,explanation,18);
        steps=column();add(paper,steps,6);add(panel,paper,14);
        statusText=text("",16,BLUE,true);add(panel,statusText,16);
        print=button("打印小票",true);retry=button("重试打印",true);cancel=button("取消打印请求",false);
        add(panel,print,16);add(panel,retry,10);add(panel,cancel,10);
        foot=text("",13,MUTED,false);foot.setGravity(Gravity.CENTER);add(panel,foot,16);
        back.setOnClickListener(v->{Model m=model;if(m!=null&&m.backEnabled&&current(m))this.actions.onBack(m.sessionId);});
        print.setOnClickListener(v->{Model m=model;if(m!=null&&m.printEnabled&&current(m))this.actions.onPrintRequested(m.sessionId);});
        retry.setOnClickListener(v->{Model m=model;if(m!=null&&m.retryEnabled&&current(m))this.actions.onRetryRequested(m.sessionId);});
        cancel.setOnClickListener(v->{Model m=model;if(m!=null&&m.cancelEnabled&&current(m))this.actions.onCancelRequested(m.sessionId,m.jobId);});
        clearDisplay();
    }
    /** Host binds current flow session before render; null clears personal fields and disables all actions. */
    public void bindSession(String sessionId) {
        checkMain();if(sessionId!=null&&empty(sessionId))throw new IllegalArgumentException("empty session");
        if(sessionId!=null&&sessionId.equals(boundSession))return;
        boundSession=sessionId;clearDisplay();
    }
    /** Rejects old-session models. Host must independently recheck document eligibility before display/submit. */
    public boolean render(Model m) {
        checkMain();if(m==null)throw new IllegalArgumentException("model required");if(!current(m))return false;model=m;
        mode.setText(m.simulation?"虚构测试":"正式模式");
        banner.setText(m.simulation?"模拟样张 / 非有效凭条":"当前会话小票预览");
        banner.setVisibility(View.VISIBLE);paper.setVisibility(m.document==null?View.GONE:View.VISIBLE);
        steps.removeAllViews();event.setText("");title.setText("");person.setText("");
        if(m.document!=null){CheckInReceipt.Document d=m.document;event.setText(d.eventHeading);title.setText(d.title);
            person.setText("姓名："+d.name+"\n俱乐部 / 团体："+(empty(d.team)?"未提供":d.team)+"\n组别："+d.group+"\n比赛号码："+d.bib);
            for(CheckInReceipt.Step s:d.steps){LinearLayout line=row();line.setGravity(Gravity.TOP);line.setPadding(0,dp(12),0,dp(12));
                TextView box=text(s.checked?"☑":"☐",23,INK,true);box.setContentDescription(s.checked?"已勾选":"未勾选");line.addView(box,new LinearLayout.LayoutParams(dp(36),LayoutParams.WRAP_CONTENT));
                LinearLayout detail=column();detail.addView(text(s.label,18,INK,true));
                int color=s.meaning==CheckInReceipt.Meaning.REQUIRED||s.meaning==CheckInReceipt.Meaning.CONFIRM?0xff8b5b0f:MUTED;
                add(detail,text(s.note,15,color,false),4);line.addView(detail,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));steps.addView(line);}}
        String status=label(m);if(!empty(m.notice))status+="\n"+m.notice;statusText.setText(status);
        statusText.setTextColor(m.status==ReceiptPrintController.Status.FAILED?0xffa13730:BLUE);
        boolean reprint=m.status==ReceiptPrintController.Status.SIMULATED||m.status==ReceiptPrintController.Status.ACCEPTED;
        print.setText(reprint?(m.simulation?"再次模拟打印（不连接打印机）":"再次打印小票")
                :(m.simulation?"模拟打印（不连接打印机）":"打印小票"));retry.setText(m.simulation?"重试模拟打印":"重试打印");
        enable(back,m.backEnabled);enable(print,m.printEnabled);enable(retry,m.retryEnabled);enable(cancel,m.cancelEnabled);
        retry.setVisibility(m.status==ReceiptPrintController.Status.FAILED||m.status==ReceiptPrintController.Status.CANCELLED?View.VISIBLE:View.GONE);
        cancel.setVisibility(m.status==ReceiptPrintController.Status.SENDING?View.VISIBLE:View.GONE);
        foot.setText(m.simulation?"仅供模拟测试，不能作为报到凭证\n模拟打印不会连接真实打印器":"预览不代表已经出纸\n签署声明书、保险核验与赛事包发放仍须现场办理");return true;
    }
    private static String label(Model m){if(m.document==null)return "暂无可预览小票，请先完成本次核验并保存结果。";
        switch(m.status){case SENDING:return m.simulation?"正在提交模拟打印请求…":"正在提交打印请求…";
            case ACCEPTED:return "打印请求已受理，实际出纸待现场确认。"+(m.printEnabled?"\n再次打印前请先核对出纸情况，避免重复出纸。":"");
            case SIMULATED:return "模拟打印完成 · 未连接真实打印机"+(m.printEnabled?"\n可显式再次模拟打印，不会自动重复提交。":"");
            case FAILED:return "打印请求未完成："+failureLabel(m.failure);
            case CANCELLED:return "打印请求已取消："+failureLabel(m.failure);
            default:return m.printEnabled?"请核对当前小票内容后提交。":"当前仅可预览，打印条件尚未满足。";}}
    private static String failureLabel(ReceiptPrintController.Failure f){switch(f){case INELIGIBLE:return "当前会话不具备打印资格";case WRONG_MODE:return "文档与打印模式不一致";case NOT_CONNECTED:return "打印机未连接";case PRINTER_ERROR:return "打印接口异常，出纸情况请现场核对";case TIMEOUT:return "请求超时，出纸情况请现场核对后再重试";case STALE_SESSION:return "会话已失效，请返回当前核验";case CANCELLED:return "已取消，出纸情况请现场核对";default:return "请核对当前状态后重试";}}
    private void clearDisplay(){model=null;mode.setText("待初始化");banner.setText("");banner.setVisibility(View.GONE);paper.setVisibility(View.GONE);
        event.setText("");title.setText("");person.setText("");steps.removeAllViews();statusText.setText("暂无当前会话小票");foot.setText("只显示当前会话内容，不从历史记录补打印");
        enable(back,false);enable(print,false);enable(retry,false);enable(cancel,false);retry.setVisibility(View.GONE);cancel.setVisibility(View.GONE);}
    @Override protected void onDetachedFromWindow(){bindSession(null);super.onDetachedFromWindow();}
    private boolean current(Model m){return boundSession!=null&&boundSession.equals(m.sessionId);}
    private static void checkMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("view operations on main thread");}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static void enable(Button b,boolean enabled){b.setEnabled(enabled);b.setAlpha(enabled?1f:.45f);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void add(LinearLayout p,View v,int gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);lp.topMargin=dp(gap);p.addView(v,lp);}
    private GradientDrawable background(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private Button button(String label,boolean primary){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(primary?Color.WHITE:BLUE);b.setMinimumHeight(dp(50));b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setBackground(background(primary?BLUE:Color.WHITE,primary?BLUE:LINE,9));return b;}
}
