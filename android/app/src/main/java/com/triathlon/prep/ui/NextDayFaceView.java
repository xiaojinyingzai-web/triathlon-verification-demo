package com.triathlon.prep.ui;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.*;
import android.widget.*;

/** Passive FACE page; shared staff mode bar belongs to Host. No SDK/bitmap/persistence. */
public final class NextDayFaceView extends ScrollView {
    public enum State { DISABLED,BLOCKED,READY,CAPTURING,CHECKING,MATCHED,MANUAL_REQUIRED,FAILED,CANCELLED }
    public enum LibraryState { ABSENT,EMPTY,LOADING,READY,DIRTY,INCOMPATIBLE,CORRUPT }
    public enum Readiness { SIMULATION,UNVERIFIED_LICENSE,UNVERIFIED_PARAMETERS,READY }
    public enum StepResult { NOT_RUN,RUNNING,PASSED,FAILED,UNKNOWN }
    public enum PrimaryAction { NONE,ENABLE_MODULE,START_CHECK,RETRY_PREPARATION,RETRY_SAVE }
    public static final class Person {
        public final String personKey,bib,name,group;
        public Person(String personKey,String bib,String name,String group){if(personKey==null||personKey.isEmpty()||bib==null||name==null||group==null)throw new IllegalArgumentException("Person mapping required");this.personKey=personKey;this.bib=bib;this.name=name;this.group=group;}
    }
    public static final class Model {
        public final boolean simulation;public final String eventName,batchId,modelLabel,notice,operationId,viewToken,requestToken;
        public final long revision,libraryRevision,surfaceAcknowledgedRevision;public final boolean previewReady;public final int storedCount,readyCount,candidateCount;
        public final int bufferWidth,bufferHeight;
        public final State state;public final LibraryState libraryState;public final Readiness readiness;
        public final StepResult capture,quality,liveness,recognition;public final Double bestScore,runnerUpScore;public final Person person;
        public final PrimaryAction primaryAction;public final boolean primaryEnabled,cancelEnabled,nextEnabled,manualEnabled,backEnabled;
        public Model(boolean simulation,String eventName,String batchId,String modelLabel,String notice,String operationId,String viewToken,String requestToken,long revision,long libraryRevision,long surfaceAcknowledgedRevision,boolean previewReady,int storedCount,int readyCount,int candidateCount,State state,LibraryState libraryState,Readiness readiness,StepResult capture,StepResult quality,StepResult liveness,StepResult recognition,Double bestScore,Double runnerUpScore,Person person,PrimaryAction primaryAction,boolean primaryEnabled,boolean cancelEnabled,boolean nextEnabled,boolean manualEnabled,boolean backEnabled){
            this(simulation,eventName,batchId,modelLabel,notice,operationId,viewToken,requestToken,revision,libraryRevision,surfaceAcknowledgedRevision,previewReady,storedCount,readyCount,candidateCount,state,libraryState,readiness,capture,quality,liveness,recognition,bestScore,runnerUpScore,person,primaryAction,primaryEnabled,cancelEnabled,nextEnabled,manualEnabled,backEnabled,0,0);
        }
        public Model(boolean simulation,String eventName,String batchId,String modelLabel,String notice,String operationId,String viewToken,String requestToken,long revision,long libraryRevision,long surfaceAcknowledgedRevision,boolean previewReady,int storedCount,int readyCount,int candidateCount,State state,LibraryState libraryState,Readiness readiness,StepResult capture,StepResult quality,StepResult liveness,StepResult recognition,Double bestScore,Double runnerUpScore,Person person,PrimaryAction primaryAction,boolean primaryEnabled,boolean cancelEnabled,boolean nextEnabled,boolean manualEnabled,boolean backEnabled,int bufferWidth,int bufferHeight){
            if(!((bufferWidth==0&&bufferHeight==0)||(bufferWidth>0&&bufferWidth<=8192&&bufferHeight>0&&bufferHeight<=8192)))throw new IllegalArgumentException("Trusted camera buffer dimensions required");this.bufferWidth=bufferWidth;this.bufferHeight=bufferHeight;
            if(surfaceAcknowledgedRevision<0||previewReady&&surfaceAcknowledgedRevision==0)throw new IllegalArgumentException("Valid surface acknowledgement required");this.surfaceAcknowledgedRevision=surfaceAcknowledgedRevision;this.previewReady=previewReady;
            if(eventName==null||batchId==null||modelLabel==null||notice==null||operationId==null||viewToken==null||requestToken==null||revision<0||libraryRevision<0||storedCount<0||readyCount<0||readyCount>storedCount||candidateCount<0||state==null||libraryState==null||readiness==null||capture==null||quality==null||liveness==null||recognition==null||primaryAction==null)throw new IllegalArgumentException("Invalid face presentation");
            if(person!=null&&state!=State.MATCHED||libraryState!=LibraryState.READY&&readyCount!=0||primaryEnabled&&primaryAction==PrimaryAction.NONE)throw new IllegalArgumentException("Invalid person/library/action presentation");
            if(bestScore!=null&&!Double.isFinite(bestScore)||runnerUpScore!=null&&!Double.isFinite(runnerUpScore))throw new IllegalArgumentException("Finite observations required");
            this.simulation=simulation;this.eventName=eventName;this.batchId=batchId;this.modelLabel=modelLabel;this.notice=notice;this.operationId=operationId;this.viewToken=viewToken;this.requestToken=requestToken;this.revision=revision;this.libraryRevision=libraryRevision;this.storedCount=storedCount;this.readyCount=readyCount;this.candidateCount=candidateCount;this.state=state;this.libraryState=libraryState;this.readiness=readiness;this.capture=capture;this.quality=quality;this.liveness=liveness;this.recognition=recognition;this.bestScore=bestScore;this.runnerUpScore=runnerUpScore;this.person=person;this.primaryAction=primaryAction;this.primaryEnabled=primaryEnabled;this.cancelEnabled=cancelEnabled;this.nextEnabled=nextEnabled;this.manualEnabled=manualEnabled;this.backEnabled=backEnabled;
        }
    }
    public interface Actions {
        void onPrimaryAction(String operationId,String requestToken,long revision);void onCancel(String operationId,String requestToken,long revision);void onNextPerson(String operationId,String requestToken,long revision);void onManualReview(String operationId,String requestToken,long revision);void onBack(String operationId,String requestToken,long revision);
        void onSurfaceAvailable(String viewToken,long surfaceRevision,Surface surface,int width,int height);void onSurfaceDestroyed(String viewToken,long surfaceRevision);
        default void onSurfaceFrameUpdated(String viewToken,long surfaceRevision,long textureTimestampNanos){}
    }
    private final Actions actions;private final TextureView preview;private final TextView mode,library,status,steps,person,scores,placeholder;
    private final Button primary,cancel,next,manual,back;private Model model;private final UiActionTicket ticket=new UiActionTicket();
    private Surface surface;private String surfaceToken;private long surfaceRevision;private int surfaceWidth,surfaceHeight;
    private final SurfaceFrameGuard frameGuard=new SurfaceFrameGuard();
    static final class SurfaceFrameGuard {
        private String token;private long revision,lastTimestamp;private Object texture;
        void bind(String token,long revision,Object texture){this.token=token;this.revision=revision;this.texture=texture;lastTimestamp=0;}
        void clear(){token=null;texture=null;revision=lastTimestamp=0;}
        boolean matches(String token,long revision,Object texture){return this.token!=null&&this.token.equals(token)&&this.revision==revision&&this.texture==texture;}
        boolean accept(String token,long revision,Object texture,long timestamp){if(!matches(token,revision,texture)||timestamp<=lastTimestamp||timestamp<=0)return false;lastTimestamp=timestamp;return true;}
    }
    public NextDayFaceView(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("Actions required");this.actions=actions;setSaveEnabled(false);setSaveFromParentEnabled(false);setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));addView(panel,new ScrollView.LayoutParams(-1,-2));
        panel.addView(text("刷脸核验（百度）",25,true));mode=text("等待状态同步 · 未执行核验",15,true);add(panel,mode,8);
        library=text("赛事与库状态待同步",15,false);add(panel,library,12);
        FrameLayout camera=new FrameLayout(context);camera.setBackgroundColor(0xffe8edf2);add(panel,camera,16);camera.getLayoutParams().height=dp(300);
        preview=new TextureView(context);camera.addView(preview,new FrameLayout.LayoutParams(-1,-1));placeholder=text("预览待连接\n预览不代表抓拍、活体或识别成功",18,false);placeholder.setGravity(Gravity.CENTER);camera.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){bindSurface(true);}public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){bindSurface(true);}public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){releaseSurface();return true;}public void onSurfaceTextureUpdated(SurfaceTexture texture){if(model==null||surface==null||!isAttachedToWindow()||!model.viewToken.equals(surfaceToken)||!frameGuard.matches(surfaceToken,surfaceRevision,texture))return;long timestamp=texture.getTimestamp();if(!frameGuard.accept(surfaceToken,surfaceRevision,texture,timestamp))return;String token=surfaceToken;long revision=surfaceRevision;actions.onSurfaceFrameUpdated(token,revision,timestamp);if(model!=null&&!model.simulation&&surfaceAcknowledged())placeholder.setVisibility(GONE);}});
        status=text("模块尚未启用",21,true);add(panel,status,16);steps=text("",15,false);add(panel,steps,10);person=text("未确认当前人员",19,true);add(panel,person,12);scores=text("",14,false);add(panel,scores,6);
        primary=button("等待操作条件");cancel=button("取消当前核验");next=button("下一位");manual=button("请求人工处理");back=button("返回");
        add(panel,primary,14);LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);for(Button b:new Button[]{cancel,next,manual})row.addView(b,new LinearLayout.LayoutParams(0,-2,1));add(panel,row,8);add(panel,back,8);
        add(panel,text("刷脸仅产生独立核验记录，不自动报到或打印。建库/许可/准确率仍需真机验证。",13,false),12);
        primary.setOnClickListener(v->dispatch(0));cancel.setOnClickListener(v->dispatch(1));next.setOnClickListener(v->dispatch(2));manual.setOnClickListener(v->dispatch(3));back.setOnClickListener(v->dispatch(4));refresh();
    }
    public void render(Model next){main();if(next==null)throw new IllegalArgumentException("Model required");Model old=model;
        if(old!=null&&(!old.viewToken.equals(next.viewToken)||!old.operationId.equals(next.operationId)||old.libraryRevision!=next.libraryRevision)){person.setText("未确认当前人员");scores.setText("");}
        if(surface!=null&&(!next.viewToken.equals(surfaceToken)||next.bufferWidth!=surfaceWidth||next.bufferHeight!=surfaceHeight))releaseSurface();model=next;ticket.update(next.requestToken,next.revision);
        mode.setText(next.simulation||next.readiness==Readiness.SIMULATION?"TEST模拟核验 · 非真实识别或活体通过":next.readiness==Readiness.UNVERIFIED_LICENSE?"百度许可未验证 · 不可正式识别":next.readiness==Readiness.UNVERIFIED_PARAMETERS?"识别参数未校准 · 不可正式识别":"当前为百度刷脸核验 · 不自动报到打印");
        library.setText("赛事："+next.eventName+"\n批次："+next.batchId+"\n模型："+next.modelLabel+" · 库版本："+next.libraryRevision+"\n库状态："+libraryLabel(next.libraryState)+" · 已登记 "+next.storedCount+" · 索引可用 "+next.readyCount);
        status.setText(stateLabel(next.state)+(next.notice.isEmpty()?"":"\n"+next.notice));steps.setText("采集："+stepLabel(next.capture)+"   质量："+stepLabel(next.quality)+"\n活体："+stepLabel(next.liveness)+"   识别："+stepLabel(next.recognition));
        person.setText(next.state==State.MATCHED&&next.person!=null?"当前匹配："+next.person.name+"\n比赛号码："+next.person.bib+"   组别："+next.person.group:next.state==State.MANUAL_REQUIRED?"需工作人员处理 · 候选数 "+next.candidateCount+"\n不自动选择候选或放行":"未确认当前人员");
        boolean observe=next.state==State.MATCHED||next.state==State.MANUAL_REQUIRED;scores.setText(observe?"分数观察："+(next.bestScore==null?"未提供":next.bestScore)+" · 次高："+(next.runnerUpScore==null?"未提供":next.runnerUpScore):"");
        primary.setText(next.primaryAction==PrimaryAction.ENABLE_MODULE?"准备刷脸模块（不切换现场模式）":next.primaryAction==PrimaryAction.START_CHECK?"开始刷脸核验":next.primaryAction==PrimaryAction.RETRY_PREPARATION?"重试准备":next.primaryAction==PrimaryAction.RETRY_SAVE?"重试保存本次核验记录":"等待操作条件");
        if(next.simulation)placeholder.setText("TEST模拟预览占位\n不证明真实相机、活体或1:N效果");else placeholder.setText("预览待连接\n预览不代表核验通过");placeholder.setVisibility(VISIBLE);refresh();bindSurface(false);
    }
    private void dispatch(int kind){Model m=model;if(m==null||!isAttachedToWindow()||!allowed(kind,m)||!ticket.take())return;
        if(kind==0||kind==2||kind==4){person.setText("未确认当前人员");scores.setText("");}refresh();
        switch(kind){case 0:actions.onPrimaryAction(m.operationId,m.requestToken,m.revision);break;case 1:actions.onCancel(m.operationId,m.requestToken,m.revision);break;case 2:actions.onNextPerson(m.operationId,m.requestToken,m.revision);break;case 3:actions.onManualReview(m.operationId,m.requestToken,m.revision);break;default:releaseSurface();actions.onBack(m.operationId,m.requestToken,m.revision);}
    }
    private boolean allowed(int kind,Model m){if(m.operationId.isEmpty())return false;switch(kind){case 0:if(!m.primaryEnabled||m.primaryAction==PrimaryAction.NONE)return false;return m.primaryAction!=PrimaryAction.START_CHECK||m.state==State.READY&&m.libraryState==LibraryState.READY&&(m.readiness==Readiness.READY||m.simulation&&m.readiness==Readiness.SIMULATION)&&(m.simulation||surfaceAcknowledged());case 1:return m.cancelEnabled;case 2:return m.nextEnabled;case 3:return m.manualEnabled;default:return m.backEnabled;}}
    private boolean surfaceAcknowledged(){return model!=null&&surface!=null&&model.viewToken.equals(surfaceToken)&&model.previewReady&&model.surfaceAcknowledgedRevision==surfaceRevision;}
    private void refresh(){Button[] buttons={primary,cancel,next,manual,back};for(int i=0;i<buttons.length;i++){boolean ready=model!=null&&ticket.available()&&allowed(i,model);buttons[i].setEnabled(ready);buttons[i].setAlpha(ready?1f:.45f);}}
    private void bindSurface(boolean resize){if(model==null||model.viewToken.isEmpty()||model.bufferWidth==0||model.bufferHeight==0||!isAttachedToWindow()||!preview.isAvailable())return;
        if(!resize&&surface!=null&&model.viewToken.equals(surfaceToken)&&surfaceWidth==model.bufferWidth&&surfaceHeight==model.bufferHeight)return;
        releaseSurface();if(model==null||model.viewToken.isEmpty()||model.bufferWidth==0||model.bufferHeight==0||!isAttachedToWindow()||!preview.isAvailable())return;SurfaceTexture texture=preview.getSurfaceTexture();if(texture==null)return;
        int width=model.bufferWidth,height=model.bufferHeight;String token=model.viewToken;
        texture.setDefaultBufferSize(width,height);Surface created=new Surface(texture);
        surfaceRevision++;surfaceToken=token;surfaceWidth=width;surfaceHeight=height;surface=created;frameGuard.bind(token,surfaceRevision,texture);refresh();actions.onSurfaceAvailable(token,surfaceRevision,created,width,height);
    }
    private void releaseSurface(){Surface previous=surface;String oldToken=surfaceToken;long oldRevision=surfaceRevision;surface=null;surfaceToken=null;surfaceWidth=surfaceHeight=0;frameGuard.clear();
        if(previous!=null){try{actions.onSurfaceDestroyed(oldToken,oldRevision);}finally{previous.release();}}placeholder.setVisibility(VISIBLE);refresh();
    }
    public void unbind(){main();model=null;ticket.clear();releaseSurface();person.setText("未确认当前人员");scores.setText("");steps.setText("");library.setText("赛事与库状态待同步");status.setText("页面未绑定核验会话");mode.setText("未执行核验");refresh();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();bindSurface(false);}
    @Override protected void onDetachedFromWindow(){unbind();super.onDetachedFromWindow();}
    private static String stateLabel(State state){switch(state){case DISABLED:return "模块尚未启用";case BLOCKED:return "当前不可刷脸核验";case READY:return "请面对相机，等待开始";case CAPTURING:return "正在采集";case CHECKING:return "正在检查质量、活体与1:N";case MATCHED:return "已匹配当前人员";case MANUAL_REQUIRED:return "无法可靠确认，请工作人员处理";case FAILED:return "本次核验未完成";default:return "本次核验已取消";}}
    private static String libraryLabel(LibraryState state){switch(state){case ABSENT:return "未建立";case EMPTY:return "空库";case LOADING:return "加载中";case READY:return "索引就绪";case DIRTY:return "待重建索引";case INCOMPATIBLE:return "模型不兼容";default:return "库损坏";}}
    private static String stepLabel(StepResult step){switch(step){case NOT_RUN:return "未执行";case RUNNING:return "进行中";case PASSED:return "通过";case FAILED:return "未通过";default:return "未知";}}
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI main thread required");}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout c=new LinearLayout(getContext());c.setOrientation(LinearLayout.VERTICAL);return c;}
    private TextView text(String value,int size,boolean bold){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(0xff162638);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setSaveEnabled(false);return t;}
    private Button button(String value){Button b=new Button(getContext());b.setText(value);b.setTextColor(0xff1769b7);b.setAllCaps(false);b.setMinimumHeight(dp(48));return b;}
    private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(gap);parent.addView(child,p);}
}
