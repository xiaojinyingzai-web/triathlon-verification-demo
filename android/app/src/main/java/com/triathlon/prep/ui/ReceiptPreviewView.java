package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.triathlon.prep.core.CheckInReceipt;
import com.triathlon.prep.core.ReceiptPrintController;

/** Passive receipt display. Host owns eligibility, transport, jobs and document/session lifetime. */
public final class ReceiptPreviewView extends ScrollView {
    public interface Actions {
        default void onPrintRequested(String sessionId){}
        default void onRetryRequested(String sessionId){}
        default void onPrintRequested(String sessionId,String requestToken,long requestRevision,boolean confirmed){}
        default void onRetryRequested(String sessionId,String requestToken,long requestRevision,boolean confirmed){}
        void onCancelRequested(String sessionId, String jobId);
        void onBack(String sessionId);
    }
    public static final class Model {
        public final String sessionId, jobId, notice;
        public final String requestToken;public final long requestRevision;public final boolean printRequiresConfirmation;
        public final boolean simulation, printEnabled, retryEnabled, cancelEnabled, backEnabled;
        public final CheckInReceipt.Document document;
        public final ReceiptPrintController.Raster raster;
        public final ReceiptPrintController.Status status;
        public final ReceiptPrintController.Failure failure;
        public Model(String sessionId, boolean simulation, CheckInReceipt.Document document,
                ReceiptPrintController.Status status, ReceiptPrintController.Failure failure,
                String jobId, String notice, boolean printEnabled, boolean retryEnabled,
                boolean cancelEnabled, boolean backEnabled) {
            this(sessionId,simulation,document,status,failure,jobId,notice,printEnabled,retryEnabled,cancelEnabled,backEnabled,null);
        }
        public Model(String sessionId, boolean simulation, CheckInReceipt.Document document,
                ReceiptPrintController.Status status, ReceiptPrintController.Failure failure,
                String jobId, String notice, boolean printEnabled, boolean retryEnabled,
                boolean cancelEnabled, boolean backEnabled,ReceiptPrintController.Raster raster) {
            this(sessionId,simulation,document,status,failure,jobId,notice,printEnabled,retryEnabled,cancelEnabled,backEnabled,raster,"",0,true);
        }
        public Model(String sessionId,boolean simulation,CheckInReceipt.Document document,
                ReceiptPrintController.Status status,ReceiptPrintController.Failure failure,
                String jobId,String notice,boolean printEnabled,boolean retryEnabled,boolean cancelEnabled,
                boolean backEnabled,ReceiptPrintController.Raster raster,String requestToken,long requestRevision,boolean printRequiresConfirmation){
            if(requestToken==null||requestRevision<0)throw new IllegalArgumentException("request token/revision required");
            if(empty(sessionId)||status==null||failure==null)throw new IllegalArgumentException("session/status/failure required");
            if(document!=null&&(!sessionId.equals(document.sessionId)||simulation!=document.simulation))
                throw new IllegalArgumentException("document session/mode mismatch");
            if(raster!=null&&document==null)throw new IllegalArgumentException("raster requires document");
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
            this.raster=raster;
            this.requestToken=requestToken;this.requestRevision=requestRevision;this.printRequiresConfirmation=printRequiresConfirmation;
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7,LINE=0xffdce4ec;
    private final Actions actions;
    private final TextView mode,banner,explanation,statusText,foot;
    private final LinearLayout paper;
    private final ImageView paperImage;
    private final Button back, print, retry, cancel;
    private String boundSession;
    private Model model;
    private ReceiptPrintController.Raster displayedRaster;
    private final ReprintConfirmation confirmation=new ReprintConfirmation();
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
        paperImage=new ImageView(context);paperImage.setAdjustViewBounds(true);paperImage.setScaleType(ImageView.ScaleType.FIT_CENTER);paperImage.setContentDescription("同源小票图像纸面");paper.addView(paperImage,new LinearLayout.LayoutParams(-1,-2));
        add(panel,paper,14);((LinearLayout.LayoutParams)paper.getLayoutParams()).gravity=Gravity.CENTER_HORIZONTAL;
        explanation=text("",15,0xff8b5b0f,true);add(panel,explanation,12);
        statusText=text("",16,BLUE,true);add(panel,statusText,16);
        print=button("打印小票",true);retry=button("重试打印",true);cancel=button("取消打印请求",false);
        add(panel,print,16);add(panel,retry,10);add(panel,cancel,10);
        foot=text("",13,MUTED,false);foot.setGravity(Gravity.CENTER);add(panel,foot,16);
        back.setOnClickListener(v->{Model m=model;if(m!=null&&m.backEnabled&&current(m)){confirmation.dismiss();this.actions.onBack(m.sessionId);}});
        print.setOnClickListener(v->request(false));
        retry.setOnClickListener(v->request(true));
        cancel.setOnClickListener(v->{Model m=model;if(m!=null&&m.cancelEnabled&&current(m))this.actions.onCancelRequested(m.sessionId,m.jobId);});
        clearDisplay();
        confirmation.setOnClosed(()->{Model m=model;boolean ready=m!=null&&current(m)&&confirmation.available();enable(print,ready&&eligible(m,false));enable(retry,ready&&eligible(m,true));});
    }
    /** Host binds current flow session before render; null clears personal fields and disables all actions. */
    public void bindSession(String sessionId) {
        checkMain();if(sessionId!=null&&empty(sessionId))throw new IllegalArgumentException("empty session");
        if(sessionId!=null&&sessionId.equals(boundSession))return;
        boundSession=sessionId;clearDisplay();
    }
    /** Rejects old-session models. Host must independently recheck document eligibility before display/submit. */
    public boolean render(Model m) {
        checkMain();if(m==null)throw new IllegalArgumentException("model required");if(!current(m))return false;
        if(model!=null&&(model.document!=m.document||model.raster!=m.raster))confirmation.dismiss();model=m;
        mode.setText(m.simulation?"虚构测试":"正式模式");
        banner.setText(m.simulation?"模拟测试预览 / 非有效凭条":"当前会话小票预览");banner.setVisibility(View.VISIBLE);
        paper.setVisibility(View.GONE);explanation.setText("");explanation.setVisibility(View.GONE);
        boolean imageFailed=false;
        if(m.document!=null&&m.raster!=null){try{if(displayedRaster!=m.raster||paperImage.getDrawable()==null){Bitmap bitmap=ReceiptBitmapRenderer.paperBitmap(m.raster);paperImage.setImageBitmap(bitmap);displayedRaster=m.raster;}paper.setVisibility(View.VISIBLE);}catch(RuntimeException error){imageFailed=true;displayedRaster=null;paperImage.setImageDrawable(null);}}
        else{displayedRaster=null;paperImage.setImageDrawable(null);}
        if(m.document!=null&&m.document.unknownDeepWater){explanation.setText("深水测试信息待确认，请由工作人员核对。");explanation.setVisibility(View.VISIBLE);}
        String status=imageFailed?"小票图像无法显示，请检查当前模板。":m.document!=null&&m.raster==null?"小票图像准备中，尚未生成同源预览。":label(m);if(!empty(m.notice))status+="\n"+m.notice;statusText.setText(status);
        statusText.setTextColor(m.status==ReceiptPrintController.Status.FAILED?0xffa13730:BLUE);
        boolean reprint=m.status==ReceiptPrintController.Status.SIMULATED||m.status==ReceiptPrintController.Status.ACCEPTED;
        print.setText(reprint?(m.simulation?"再次模拟打印（不连接打印机）":"再次打印小票")
                :(m.simulation?"模拟打印（不连接打印机）":"打印小票"));retry.setText(m.simulation?"重试模拟打印":"重试打印");
        confirmation.update(actionIdentity(m),m.requestToken,m.requestRevision,(m.printEnabled||m.retryEnabled)&&m.raster!=null&&!imageFailed);
        enable(back,m.backEnabled);enable(print,m.printEnabled&&m.raster!=null&&!imageFailed&&confirmation.available());enable(retry,m.retryEnabled&&m.raster!=null&&!imageFailed&&confirmation.available());enable(cancel,m.cancelEnabled);
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
    private void clearDisplay(){confirmation.dismiss();confirmation.update("","",0,false);model=null;mode.setText("待初始化");banner.setText("");banner.setVisibility(View.GONE);paper.setVisibility(View.GONE);
        displayedRaster=null;paperImage.setImageDrawable(null);explanation.setText("");explanation.setVisibility(View.GONE);statusText.setText("暂无当前会话小票");foot.setText("只显示当前会话内容，不从历史记录补打印");
        enable(back,false);enable(print,false);enable(retry,false);enable(cancel,false);retry.setVisibility(View.GONE);cancel.setVisibility(View.GONE);}
    @Override protected void onDetachedFromWindow(){bindSession(null);super.onDetachedFromWindow();}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int available=MeasureSpec.getSize(widthSpec);
        if(available>0){LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)paper.getLayoutParams();
            int width=Math.max(1,Math.min(dp(520),available-dp(36)));
            if(params.width!=width){params.width=width;paper.setLayoutParams(params);}
        }
        super.onMeasure(widthSpec,heightSpec);
    }
    private boolean current(Model m){return boundSession!=null&&boundSession.equals(m.sessionId);}
    private static String actionIdentity(Model m){return m.sessionId+"/"+m.status+"/"+m.printEnabled+"/"+m.retryEnabled+"/"+m.printRequiresConfirmation;}
    private void request(boolean retryAction){Model captured=model;if(!eligible(captured,retryAction)||!isAttachedToWindow())return;
        boolean needs=retryAction||captured.printRequiresConfirmation;
        confirmation.request(getContext(),needs,()->sameAction(captured)&&eligible(model,retryAction),confirmed->{
            enable(print,false);enable(retry,false);
            if(retryAction)actions.onRetryRequested(captured.sessionId,captured.requestToken,captured.requestRevision,confirmed);
            else actions.onPrintRequested(captured.sessionId,captured.requestToken,captured.requestRevision,confirmed);
        });
    }
    private boolean eligible(Model m,boolean retryAction){return m!=null&&current(m)&&m.raster!=null&&paper.getVisibility()==View.VISIBLE&&!empty(m.requestToken)&&(retryAction?m.retryEnabled:m.printEnabled);}
    private boolean sameAction(Model captured){return model!=null&&captured.sessionId.equals(model.sessionId)&&captured.requestToken.equals(model.requestToken)&&captured.requestRevision==model.requestRevision&&actionIdentity(captured).equals(actionIdentity(model))&&captured.document==model.document&&captured.raster==model.raster;}
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
