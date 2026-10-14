package com.triathlon.prep.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.triathlon.prep.ReaderOnlyContract;

/** Debug reader-only passive panel. No USB, private input, SDK, pictures or persistent results. */
public final class DebugReaderOnlyPanel implements ReaderOnlyContract.PanelFactory {
    public DebugReaderOnlyPanel() {}
    @Override public ReaderOnlyContract.Panel create(Activity activity,ReaderOnlyContract.Actions actions){
        main();if(activity==null||actions==null)throw new IllegalArgumentException("Reader panel owner required");return new Content(activity,actions);
    }
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI main thread required");}
    static ReaderOnlyContract.Phase reasonPhase(ReaderOnlyContract.Model model){return model.phase==ReaderOnlyContract.Phase.CLOSED&&model.resultPhase!=ReaderOnlyContract.Phase.CLOSED?model.resultPhase:model.phase;}

    static final class ActionGate {
        private ReaderOnlyContract.Phase phase;
        private boolean consumed;
        void update(ReaderOnlyContract.Phase next){if(next!=phase){phase=next;consumed=false;}}
        boolean prepareAllowed(){return !consumed&&(phase==ReaderOnlyContract.Phase.NEEDS_PRIVATE_INPUT||phase==ReaderOnlyContract.Phase.CLOSED);}
        boolean readAllowed(){return !consumed&&phase==ReaderOnlyContract.Phase.PREPARED;}
        boolean takePrepare(){if(!prepareAllowed())return false;consumed=true;return true;}
        boolean takeRead(){if(!readAllowed())return false;consumed=true;return true;}
        void consume(){consumed=true;}
    }

    static final class ResultGate {
        private long epoch=-1,expires;
        private boolean suppressed,awaitingNew,displayed;
        boolean update(long next){if(next<epoch||awaitingNew&&next<=epoch)return false;if(next>epoch){epoch=next;displayed=false;expires=0;}awaitingNew=false;return true;}
        void clear(){suppressed=true;displayed=false;expires=0;}
        void start(boolean newSession){clear();suppressed=false;awaitingNew=newSession;}
        boolean visible(ReaderOnlyContract.Model model,long now){
            if(suppressed||awaitingNew||model.sessionEpoch!=epoch)return false;
            ReaderOnlyContract.Phase phase=model.phase,result=model.resultPhase;
            boolean resultPhase=result==ReaderOnlyContract.Phase.READ_OK||result==ReaderOnlyContract.Phase.MATCHED||result==ReaderOnlyContract.Phase.NOT_LISTED;
            boolean phaseAllows=phase==ReaderOnlyContract.Phase.READ_OK||phase==ReaderOnlyContract.Phase.MATCHED||phase==ReaderOnlyContract.Phase.NOT_LISTED||phase==ReaderOnlyContract.Phase.CLOSED;
            if(!resultPhase||!phaseAllows||model.maskedNumber.isEmpty())return false;
            if(!displayed){displayed=true;expires=now+30000;}
            if(now>=expires){clear();return false;}return true;
        }
        long deadline(){return expires;}
    }

    private static final class Content extends ScrollView implements ReaderOnlyContract.Panel {
        private final ReaderOnlyContract.Actions actions;
        private final ActionGate gate=new ActionGate();
        private final TextView state,notice,number,cardName,matchedName,bib,batch;
        private final Button prepare,read,clear;
        private final ResultGate results=new ResultGate();
        private final Runnable expireResult=this::clearPersonalResult;

        Content(Activity activity,ReaderOnlyContract.Actions actions){
            super(activity);this.actions=actions;setSaveEnabled(false);setFillViewport(true);setBackgroundColor(0xfff5f7fa);
            LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));addView(panel,new ScrollView.LayoutParams(-1,-2));
            Button exit=button("← 结束读卡诊断");exit.setOnClickListener(v->{clearPersonalResult();gate.consume();refresh();actions.exit();});panel.addView(exit);
            add(panel,text("设备诊断 · 身份证读取与名单匹配",25,true),14);
            add(panel,text("独立调试入口 · 仅明确点击后读取一次，不自动读卡，不进行人脸比对或打印。",15,false),10);
            LinearLayout status=card("读卡状态");state=text("等待诊断状态",19,true);notice=text("尚未准备，不代表设备可读。",15,false);add(status,state,10);add(status,notice,8);add(panel,status,16);
            LinearLayout buttons=new LinearLayout(activity);prepare=button("准备读卡诊断");read=button("读取一次身份证");Button cancel=button("取消并请求关闭");
            for(Button button:new Button[]{prepare,read,cancel})buttons.addView(button,new LinearLayout.LayoutParams(0,-2,1));add(panel,buttons,14);
            prepare.setOnClickListener(v->{if(gate.takePrepare()){clearPersonalResult();results.start(true);refresh();actions.prepare();}});
            read.setOnClickListener(v->{if(gate.takeRead()){clearPersonalResult();results.start(false);refresh();actions.readOnce();}});
            cancel.setOnClickListener(v->{clearPersonalResult();gate.consume();refresh();actions.cancel();});
            LinearLayout result=card("本次临时本地结果");number=field(result,"证件号码（仅脱敏尾4位）");cardName=field(result,"证件姓名");matchedName=field(result,"名单匹配姓名");bib=field(result,"参赛号");batch=field(result,"名单批次");add(panel,result,16);
            clear=button("清除临时个人结果");clear.setOnClickListener(v->clearPersonalResult());add(panel,clear,14);
            add(panel,text("结果最多显示30秒，可手动清除；清除不代表USB已释放。只有确认关闭后才能重新准备。\n不展示芯片照片，不保存结果、不截图、不上传。名单匹配不等于完成报到或身份核验。",14,false),14);
            refresh();
        }
        @Override public View view(){return this;}
        @Override public void render(ReaderOnlyContract.Model model){
            main();if(model==null)throw new IllegalArgumentException("Reader model required");if(!results.update(model.sessionEpoch))return;gate.update(model.phase);state.setText(title(model.phase));notice.setText(model.phase==ReaderOnlyContract.Phase.CLOSED?"USB资源已确认关闭。"+explanation(reasonPhase(model)):explanation(model.phase));
            if(model.phase==ReaderOnlyContract.Phase.CLOSED&&model.resultPhase!=ReaderOnlyContract.Phase.CLOSED)state.setText("已确认关闭 · "+(model.resultPhase==ReaderOnlyContract.Phase.FAILED?"本次读取未完成":title(model.resultPhase)));
            long now=SystemClock.uptimeMillis();boolean personal=results.visible(model,now);
            if(personal){number.setText(value(model.maskedNumber));cardName.setText(value(model.cardName));batch.setText(value(model.batchId));boolean matched=model.resultPhase==ReaderOnlyContract.Phase.MATCHED;matchedName.setText(matched?value(model.matchedName):"未确认匹配");bib.setText(matched?value(model.bib):"未确认匹配");clear.setEnabled(!model.maskedNumber.isEmpty()||!model.cardName.isEmpty()||!model.matchedName.isEmpty()||!model.bib.isEmpty()||!model.batchId.isEmpty());if(model.phase==ReaderOnlyContract.Phase.CLOSED){state.setText("已确认关闭 · "+title(model.resultPhase));notice.setText("USB资源已关闭，本次临时结果保留至清除或30秒到期。"+explanation(model.resultPhase));}removeCallbacks(expireResult);postDelayed(expireResult,Math.max(1,results.deadline()-now));}
            else eraseFields();
            // code is not an error message contract; do not echo raw backend strings as personal results.
            refresh();
        }
        @Override public void clearPersonalResult(){main();removeCallbacks(expireResult);results.clear();eraseFields();}
        private void eraseFields(){for(TextView value:new TextView[]{number,cardName,matchedName,bib,batch})if(value!=null)value.setText("未显示");if(clear!=null)clear.setEnabled(false);}
        private void refresh(){prepare.setEnabled(gate.prepareAllowed());read.setEnabled(gate.readAllowed());prepare.setAlpha(prepare.isEnabled()?1f:.45f);read.setAlpha(read.isEnabled()?1f:.45f);}
        @Override protected void onDetachedFromWindow(){clearPersonalResult();gate.consume();refresh();actions.cancel();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=View.VISIBLE){clearPersonalResult();gate.consume();if(prepare!=null&&read!=null)refresh();}}
        private static String title(ReaderOnlyContract.Phase phase){switch(phase){
            case NEEDS_PRIVATE_INPUT:return "待准备私有配置";case SERVICE_REVIEW_PENDING:return "服务政策尚待确认";
            case PREPARED:return "准备完成 · 可明确读取一次";case INITIALIZING:return "正在初始化读卡器";
            case WAITING_CARD:return "本次等待身份证";case READ_OK:return "证件已读取 · 匹配状态待确认";
            case MATCHED:return "本地名单已匹配";case NOT_LISTED:return "未在本地名单中找到";
            case NO_ROSTER:return "暂无可用本地名单";case FAILED:return "本次未完成 · 等待关闭确认";
            case CLOSING:return "正在关闭读卡资源";case CLOSED:return "已确认关闭";
            case CLOSE_UNCONFIRMED:return "关闭尚未确认 · 不可重新准备";default:throw new IllegalArgumentException("Unknown reader phase");}}
        private static String explanation(ReaderOnlyContract.Phase phase){switch(phase){
            case NEEDS_PRIVATE_INPUT:return "请先准备诊断。配置由受控入口处理，本页不输入或显示凭据。";
            case SERVICE_REVIEW_PENDING:return "服务与数据使用条件尚未确认，暂不能读取。";
            case PREPARED:return "根流程已确认配置与服务政策；请放置身份证，再点击读取一次。";
            case INITIALIZING:return "请等待初始化，本页不会重复启动或读取。";
            case WAITING_CARD:return "只处理本次明确请求；可取消并请求关闭。";
            case READ_OK:return "读证结果不是名单匹配或身份核验通过，继续等待本地匹配状态。";
            case MATCHED:return "仅本地名单匹配成功；未比对人脸、未报到、未打印。";
            case NOT_LISTED:return "请核对当前名单与批次；不会自动补录或判定通过。";
            case NO_ROSTER:return "缺少可用名单，不能确认参赛资格；暂不允许再读取。";
            case FAILED:return "本次初始化或读取未完成；是否可重开须以资源状态为准。";
            case CLOSING:return "等待根流程确认USB释放，请勿重复准备。";
            case CLOSED:return "资源已明确关闭。如需继续，请重新准备诊断。";
            case CLOSE_UNCONFIRMED:return "保留资源关闭等待，不把清除个人结果当作释放完成。";
            default:throw new IllegalArgumentException("Unknown reader phase");}}
        private static String value(String text){return text==null||text.isEmpty()?"未提供":text;}
        private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
        private LinearLayout column(){LinearLayout value=new LinearLayout(getContext());value.setOrientation(LinearLayout.VERTICAL);return value;}
        private TextView text(String caption,int size,boolean bold){TextView value=new TextView(getContext());value.setText(caption);value.setTextSize(size);value.setTextColor(0xff162638);value.setSaveEnabled(false);if(bold)value.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return value;}
        private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(gap);parent.addView(child,params);}
        private GradientDrawable background(){GradientDrawable value=new GradientDrawable();value.setColor(Color.WHITE);value.setCornerRadius(dp(12));value.setStroke(dp(1),0xffdce4ec);return value;}
        private LinearLayout card(String title){LinearLayout value=column();value.setPadding(dp(14),dp(16),dp(14),dp(16));value.setBackground(background());value.addView(text(title,20,true));return value;}
        private TextView field(LinearLayout parent,String caption){add(parent,text(caption,13,false),12);TextView value=text("未显示",16,false);add(parent,value,4);return value;}
        private Button button(String caption){Button value=new Button(getContext());value.setText(caption);value.setAllCaps(false);value.setTextSize(16);value.setTextColor(0xff1769b7);value.setMinimumHeight(dp(48));value.setBackground(background());return value;}
    }
}
