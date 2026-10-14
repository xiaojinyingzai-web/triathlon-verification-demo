package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.TextureView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Presentation only: render on the main thread; caller owns verification and bitmaps. */
public final class VerificationView extends ScrollView {
    public enum PrimaryAction { NONE, BEGIN, RETAKE, NEXT, RETRY_SAVE }
    public enum Tone { INFO, GOOD, BAD, WARNING }
    public interface Actions {
        void onPrimaryAction(PrimaryAction action);
        void onCancel();
    }
    public interface PreviewCallbacks {
        void onAvailable(String sessionId, SurfaceTexture texture, int width, int height);
        void onSizeChanged(String sessionId, SurfaceTexture texture, int width, int height);
        void onUnavailable(String sessionId);
        /** Actual TextureView update, not evidence of freshness, liveness or a matched YUV exposure. */
        default void onFrameUpdated(String sessionId,SurfaceTexture texture,long timestampNanos) {}
    }

    /** Host supplies only the current roster snapshot, never historical identity data. */
    public static final class RosterInfo {
        public final String eventName, batchLabel, notice, athleteName, bib, group, team;
        public final boolean matched;
        public RosterInfo(String eventName,String batchLabel,String notice,boolean matched,
                String athleteName,String bib,String group,String team){
            if(matched&&(blank(athleteName)||blank(bib)||blank(group)))throw new IllegalArgumentException("matched athlete fields required");
            this.eventName=eventName;this.batchLabel=batchLabel;this.notice=notice;this.matched=matched;
            this.athleteName=matched?athleteName:null;this.bib=matched?bib:null;
            this.group=matched?group:null;this.team=matched?team:null;
        }
        private static boolean blank(String s){return s==null||s.trim().isEmpty();}
    }

    /** Supply a fresh model for each snapshot, including null photos when a session ends. */
    public static final class Model {
        public final boolean simulation, cancelEnabled, primaryEnabled;
        public final int step;
        public final String title, message, instruction, name, maskedNumber;
        public final String score, liveness, saveStatus;
        public final Bitmap certificatePhoto,livePhoto;
        public final PrimaryAction primaryAction;
        public final Tone tone;
        public final RosterInfo rosterInfo;
        public final boolean scoresOnly;

        public Model(boolean simulation, int step, String title, String message,
                String instruction, String name, String maskedNumber, String score,
                String liveness, String saveStatus, Bitmap certificatePhoto,
                Bitmap livePhoto, PrimaryAction primaryAction, boolean primaryEnabled,
                boolean cancelEnabled, Tone tone) {
            this(simulation,step,title,message,instruction,name,maskedNumber,score,liveness,
                    saveStatus,certificatePhoto,livePhoto,primaryAction,primaryEnabled,cancelEnabled,tone,null);
        }
        public Model(boolean simulation, int step, String title, String message,
                String instruction, String name, String maskedNumber, String score,
                String liveness, String saveStatus, Bitmap certificatePhoto,
                Bitmap livePhoto, PrimaryAction primaryAction, boolean primaryEnabled,
                boolean cancelEnabled, Tone tone, RosterInfo rosterInfo) {
            this(simulation,step,title,message,instruction,name,maskedNumber,score,liveness,
                    saveStatus,certificatePhoto,livePhoto,primaryAction,primaryEnabled,cancelEnabled,tone,rosterInfo,false);
        }
        public Model(boolean simulation, int step, String title, String message,
                String instruction, String name, String maskedNumber, String score,
                String liveness, String saveStatus, Bitmap certificatePhoto,
                Bitmap livePhoto, PrimaryAction primaryAction, boolean primaryEnabled,
                boolean cancelEnabled, Tone tone, RosterInfo rosterInfo, boolean scoresOnly) {
            if (step < 1 || step > 4) throw new IllegalArgumentException("step must be 1..4");
            if (primaryAction == null || tone == null) throw new IllegalArgumentException("action/tone required");
            this.simulation = simulation; this.step = step; this.title = title;
            this.message = message; this.instruction = instruction; this.name = name;
            this.maskedNumber = maskedNumber; this.score = score; this.liveness = liveness;
            this.saveStatus = saveStatus; this.certificatePhoto = certificatePhoto;
            this.livePhoto = livePhoto; this.primaryAction = primaryAction;
            this.primaryEnabled = primaryEnabled; this.cancelEnabled = cancelEnabled;
            this.tone = tone;
            this.rosterInfo=rosterInfo;
            this.scoresOnly=scoresOnly;
        }
        String metricsText(){return (scoresOnly?"人脸分数：":"相似度：")+metricValue(score)+"\n"+(scoresOnly?"活体分数：":"活体：")+metricValue(liveness)+"   记录："+metricValue(saveStatus);}
        private static String metricValue(String text){return text==null||text.trim().isEmpty()?"—":text;}
    }

    private static final int INK = Color.rgb(22,38,56), MUTED = Color.rgb(99,116,135);
    private static final int BLUE = Color.rgb(23,105,183), LINE = Color.rgb(220,228,236);
    private final TextView mode, title, message, instruction, name, number, metrics, foot;
    private final TextView[] steps = new TextView[4];
    private final ImageView certificatePhoto,livePhoto;
    private final TextView certificateLabel,liveLabel;
    private final LinearLayout result, camera, body, details;
    private final TextView rosterText;
    private final PrintModeControl printModeControl;
    private final AutomaticIntakeControl automaticIntakeControl;
    private AutomaticIntakeControl.Model intakeModel;
    private final Button primary, cancel;
    private PrimaryAction currentAction = PrimaryAction.NONE;
    private final Actions actions;
    private final TextureView preview;
    private final View guide;
    private final TextView previewStatus;
    private String displaySession;
    private PreviewCallbacks previewCallbacks;
    private boolean previewNotified;

    public VerificationView(Context context, Actions actions) {
        this(context,actions,null);
    }
    public VerificationView(Context context, Actions actions, PrintModeControl.Actions printModeActions) {
        this(context,actions,printModeActions,null);
    }
    public VerificationView(Context context, Actions actions, PrintModeControl.Actions printModeActions,AutomaticIntakeControl.Actions intakeActions) {
        super(context);
        if (actions == null) throw new IllegalArgumentException("actions required");
        this.actions = actions;
        setFillViewport(true); setBackgroundColor(Color.rgb(245,247,250));
        LinearLayout panel = column();
        panel.setPadding(dp(18),dp(16),dp(18),dp(18));
        addView(panel,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));

        LinearLayout header = row();
        LinearLayout heading = column();
        heading.addView(text("人证身份核验",25,INK,true));
        heading.addView(text("本次身份证与现场人脸 1:1",14,MUTED,false));
        header.addView(heading,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        mode = text("",13,Color.rgb(130,84,9),true);
        mode.setPadding(dp(9),dp(7),dp(9),dp(7));
        mode.setBackground(background(Color.rgb(255,240,214),Color.rgb(255,240,214),8));
        header.addView(mode); panel.addView(header);

        TextView devices = text("读证、标准相机与在线比对 · 实机待验证",13,MUTED,false);
        add(panel,devices,14);
        body=column();add(panel,body,12);details=column();
        camera = column(); camera.setPadding(dp(14),dp(12),dp(14),dp(14));
        camera.setBackground(background(Color.rgb(34,51,66),Color.rgb(34,51,66),14));
        LinearLayout cameraTop = row();
        cameraTop.addView(text("现场人脸",14,Color.rgb(218,227,236),true),new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        previewStatus=text("占位 · 未连接",13,Color.rgb(180,194,207),false);cameraTop.addView(previewStatus);
        camera.addView(cameraTop);
        FrameLayout previewHost=new FrameLayout(context);
        preview=new TextureView(context);preview.setVisibility(View.INVISIBLE);
        previewHost.addView(preview,new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        guide=new FaceGuide(context);previewHost.addView(guide,new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        camera.addView(previewHost,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,0,1));
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){notifyPreviewAvailable(t,w,h);}
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){if(previewNotified&&previewCallbacks!=null)previewCallbacks.onSizeChanged(displaySession,t,w,h);}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t){releasePreviewBinding();return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture t){
                if(previewNotified&&displaySession!=null&&previewCallbacks!=null&&t==preview.getSurfaceTexture())
                    previewCallbacks.onFrameUpdated(displaySession,t,t.getTimestamp());
            }
        });
        instruction = text("",18,Color.rgb(218,227,236),true); instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(dp(8),dp(10),dp(8),dp(10));
        instruction.setBackground(background(Color.rgb(23,41,54),Color.rgb(59,81,100),8));
        camera.addView(instruction);
        LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(300));
        body.addView(camera,cameraParams);body.addView(details,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));

        LinearLayout progress = row();
        String[] labels={"1 刷身份证","2 采集/活体","3 人证比对","4 核验结果"};
        for(int i=0;i<4;i++) { steps[i]=text(labels[i],12,MUTED,false); steps[i].setGravity(Gravity.CENTER); steps[i].setPadding(0,dp(14),0,dp(14)); progress.addView(steps[i],new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1)); }
        details.addView(progress);
        automaticIntakeControl=intakeActions==null?null:new AutomaticIntakeControl(context,intakeActions);
        if(automaticIntakeControl!=null)add(details,automaticIntakeControl,8);

        LinearLayout roster=column();roster.setPadding(dp(14),dp(12),dp(14),dp(12));
        roster.setBackground(background(Color.WHITE,LINE,12));roster.addView(text("本场名单与当前选手",17,INK,true));
        rosterText=text("名单状态待同步",15,MUTED,false);add(roster,rosterText,8);add(details,roster,0);

        LinearLayout identity = row(); identity.setPadding(dp(14),dp(14),dp(14),dp(14));
        identity.setBackground(background(Color.WHITE,LINE,12));
        LinearLayout cert = column(); certificatePhoto = photo(); certificateLabel=text("待读证件照",11,MUTED,false);
        certificateLabel.setGravity(Gravity.CENTER); cert.addView(certificatePhoto,new LinearLayout.LayoutParams(dp(58),dp(70))); cert.addView(certificateLabel);
        identity.addView(cert);
        LinearLayout person = column(); person.setPadding(dp(12),0,dp(8),0);
        person.addView(text("本次身份证信息",15,INK,true)); name=text("—",21,INK,true); number=text("—",15,MUTED,false);
        person.addView(name); person.addView(number);
        identity.addView(person,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        LinearLayout live = column();livePhoto=photo();liveLabel=text("待采现场照",11,MUTED,false);liveLabel.setGravity(Gravity.CENTER);
        live.addView(livePhoto,new LinearLayout.LayoutParams(dp(58),dp(70)));live.addView(liveLabel); identity.addView(live);
        add(details,identity,12);

        result=column(); result.setPadding(dp(15),dp(14),dp(15),dp(14));
        title=text("",20,INK,true); message=text("",16,MUTED,false); metrics=text("",13,MUTED,false);
        result.addView(title);add(result,message,6);add(result,metrics,8);add(details,result,12);

        LinearLayout buttons=row(); cancel=new Button(context);cancel.setText("取消本轮");styleButton(cancel,false);
        primary=new Button(context);styleButton(primary,true);
        buttons.addView(cancel,new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,dp(54)));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(54),1);pp.leftMargin=dp(10);buttons.addView(primary,pp);add(details,buttons,14);
        cancel.setOnClickListener(v->{if(cancel.isEnabled())this.actions.onCancel();});
        primary.setOnClickListener(v->{if(primary.isEnabled()&&currentAction!=PrimaryAction.NONE)this.actions.onPrimaryAction(currentAction);});
        printModeControl=printModeActions==null?null:new PrintModeControl(context,printModeActions);
        if(printModeControl!=null)add(details,printModeControl,14);
        foot=text("",12,MUTED,false);foot.setGravity(Gravity.CENTER);add(panel,foot,14);
        primary.setEnabled(false);cancel.setEnabled(false);
    }

    /** App-wide confirmed setting, independent of the person's session. False means old constructor has no control. */
    public boolean renderPrintMode(PrintModeControl.Model model){
        checkMainThread();if(model==null)throw new IllegalArgumentException("print model required");
        if(printModeControl==null)return false;printModeControl.render(model);return true;
    }
    public boolean renderAutomaticIntake(AutomaticIntakeControl.Model model){
        checkMainThread();if(model==null)throw new IllegalArgumentException("intake model required");
        if(automaticIntakeControl==null)return false;intakeModel=model;automaticIntakeControl.render(model);refreshAutomaticPrimary();return true;
    }
    private void refreshAutomaticPrimary(){primary.setVisibility(currentAction==PrimaryAction.NONE&&intakeModel!=null&&intakeModel.state!=AutomaticIntakeControl.State.DISABLED?View.GONE:View.VISIBLE);}

    /** Must be called on main thread. It never calls begin/compare/store, nor creates a result. */
    public void render(Model m) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("model required");
        mode.setText(m.simulation?"虚构测试":"正式模式");
        title.setText(value(m.title));message.setText(value(m.message));
        instruction.setText(m.simulation&&m.primaryAction==PrimaryAction.BEGIN&&"点击开始虚构测试".equals(m.instruction)
                ?"模拟刷卡后自动核验":value(m.instruction));
        name.setText(value(m.name));number.setText(value(m.maskedNumber));
        RosterInfo r=m.rosterInfo;
        if(r==null)rosterText.setText("名单状态待同步 · 以当前核验结果为准");
        else {String roster="赛事："+value(r.eventName)+"\n批次："+value(r.batchLabel)+"\n"+value(r.notice);
            if(r.matched)roster+="\n名单姓名："+value(r.athleteName)+"   比赛号码："+value(r.bib)+"\n组别："+value(r.group)+"\n团体："+(empty(r.team)?"未提供":r.team);
            rosterText.setText(roster);}
        showPhotos(m.certificatePhoto,m.livePhoto);
        metrics.setText(m.metricsText());
        int color=BLUE,bg=Color.rgb(238,245,252);
        if(m.tone==Tone.GOOD){color=Color.rgb(35,108,71);bg=Color.rgb(237,247,241);}
        if(m.tone==Tone.BAD){color=Color.rgb(161,55,48);bg=Color.rgb(252,240,239);}
        if(m.tone==Tone.WARNING){color=Color.rgb(139,91,15);bg=Color.rgb(255,247,233);}
        title.setTextColor(color);result.setBackground(background(bg,color,10));
        for(int i=0;i<4;i++){steps[i].setTextColor(i+1==m.step?BLUE:MUTED);steps[i].setTypeface(Typeface.DEFAULT,i+1==m.step?Typeface.BOLD:Typeface.NORMAL);}
        currentAction=m.primaryAction;
        String label="等待设备就绪";
        switch(currentAction){case BEGIN:label=m.simulation?"模拟刷卡":"开始读证";break;case RETAKE:label="重新采集";break;case NEXT:label="下一位";break;case RETRY_SAVE:label="重试保存";break;default:break;}
        primary.setText(label);primary.setEnabled(m.primaryEnabled&&currentAction!=PrimaryAction.NONE);primary.setAlpha(primary.isEnabled()?1f:.45f);
        refreshAutomaticPrimary();
        cancel.setEnabled(m.cancelEnabled);cancel.setAlpha(m.cancelEnabled?1f:.45f);
        foot.setText(m.simulation?"虚构流程测试 · 任何结果均不构成身份核验\n读证、相机与在线比对仍待实机验证":"读证、相机与在线比对 · 实机验证待完成");
    }

    /** Host establishes a session before asynchronous surface/image events. Null clears it. */
    public void bindSession(String sessionId){
        checkMainThread();
        if(sessionId!=null&&sessionId.trim().isEmpty())throw new IllegalArgumentException("empty session");
        if(sessionId!=null&&sessionId.equals(displaySession))return;
        String previous=displaySession;displaySession=sessionId;releasePreviewBinding(previous);preview.setVisibility(View.INVISIBLE);showPhotos(null,null);rosterText.setText("名单状态待同步");
    }
    public boolean render(String sessionId,Model model){
        checkMainThread();if(!matches(sessionId))return false;render(model);return true;
    }
    public boolean bindPhotos(String sessionId,Bitmap chip,Bitmap scene){
        checkMainThread();if(!matches(sessionId))return false;showPhotos(chip,scene);return true;
    }
    public boolean clearPhotos(String sessionId){return bindPhotos(sessionId,null,null);}
    public boolean bindPreview(String sessionId,PreviewCallbacks callbacks){
        checkMainThread();if(!matches(sessionId))return false;
        if(callbacks==null)throw new IllegalArgumentException("callbacks required");
        releasePreviewBinding();previewCallbacks=callbacks;preview.setVisibility(View.VISIBLE);
        if(preview.isAvailable())notifyPreviewAvailable(preview.getSurfaceTexture(),preview.getWidth(),preview.getHeight());
        return true;
    }
    /** Host confirms frames, mapping and orientation; availability alone does not show a stream. */
    public boolean setPreviewVisible(String sessionId,boolean visible){
        checkMainThread();if(!matches(sessionId))return false;
        // Keep the bound texture drawable so real frame updates can reach the Host.
        // The opaque guide hides unconfirmed frames until the Host explicitly reveals them.
        boolean show=visible&&previewNotified;guide.setVisibility(show?View.INVISIBLE:View.VISIBLE);previewStatus.setText(show?"预览画面":"占位 · 未连接");return show==visible;
    }
    public boolean setPreviewTransform(String sessionId,Matrix transform){
        checkMainThread();if(!matches(sessionId))return false;
        preview.setTransform(transform==null?new Matrix():new Matrix(transform));return true;
    }
    public boolean clearPreview(String sessionId){
        checkMainThread();if(!matches(sessionId))return false;releasePreviewBinding();preview.setVisibility(View.INVISIBLE);return true;
    }
    private boolean matches(String id){return id!=null&&id.equals(displaySession);}
    private void checkMainThread(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI update on main thread");}
    private void showPhotos(Bitmap chip,Bitmap scene){
        if((chip!=null&&chip.isRecycled())||(scene!=null&&scene.isRecycled()))throw new IllegalArgumentException("recycled display bitmap");
        certificatePhoto.setImageBitmap(chip);livePhoto.setImageBitmap(scene);
        certificateLabel.setText(chip==null?"待读证件照":"本轮证件照");liveLabel.setText(scene==null?"待采现场照":"本轮现场照");
    }
    private void notifyPreviewAvailable(SurfaceTexture t,int w,int h){
        if(displaySession!=null&&previewCallbacks!=null&&!previewNotified){previewNotified=true;previewCallbacks.onAvailable(displaySession,t,w,h);}
    }
    private void releasePreviewBinding(){
        releasePreviewBinding(displaySession);
    }
    private void releasePreviewBinding(String oldSession){
        PreviewCallbacks old=previewCallbacks;boolean notified=previewNotified;
        previewCallbacks=null;previewNotified=false;guide.setVisibility(View.VISIBLE);preview.setTransform(new Matrix());previewStatus.setText("占位 · 未连接");
        if(old!=null&&notified)old.onUnavailable(oldSession);
    }
    @Override protected void onDetachedFromWindow(){bindSession(null);super.onDetachedFromWindow();}

    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
        if(camera!=null&&body!=null){
            int availableWidth=View.MeasureSpec.getSize(widthMeasureSpec);
            int viewport=View.MeasureSpec.getSize(heightMeasureSpec);
            boolean wide=availableWidth>=dp(840)&&availableWidth>viewport*1.15f;
            body.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
            body.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)camera.getLayoutParams();
            p.width=wide?0:LayoutParams.MATCH_PARENT;p.weight=wide?55:0;
            p.height=wide?Math.max(dp(240),Math.min(dp(760),viewport-dp(150))):
                    viewport>0?Math.max(dp(240),Math.min(dp(490),(int)(viewport*.39f))):dp(300);
            LinearLayout.LayoutParams right=(LinearLayout.LayoutParams)details.getLayoutParams();
            right.width=wide?0:LayoutParams.MATCH_PARENT;right.weight=wide?45:0;
            right.leftMargin=wide?dp(16):0;right.topMargin=0;
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec);
    }
    private String value(String s){return s==null||s.trim().isEmpty()?"—":s;}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String text,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);p.topMargin=dp(gap);parent.addView(child,p);}
    private GradientDrawable background(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private ImageView photo(){ImageView v=new ImageView(getContext());v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setBackground(background(Color.rgb(241,245,248),LINE,7));v.setContentDescription("本轮照片，未读取时为空");return v;}
    private void styleButton(Button b,boolean solid){b.setAllCaps(false);b.setTextSize(16);b.setMinWidth(dp(88));b.setMinimumHeight(dp(48));b.setPadding(dp(14),0,dp(14),0);b.setTextColor(solid?Color.WHITE:INK);b.setBackground(background(solid?BLUE:Color.WHITE,solid?BLUE:LINE,9));}

    private final class FaceGuide extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        FaceGuide(Context c){super(c);setBackgroundColor(Color.rgb(34,51,66));setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float availableHeight=Math.max(0,getHeight()-dp(32));
            float width=Math.min(Math.min(dp(214),getWidth()*.42f),availableHeight*214f/284f);
            float height=width*284f/214f;
            float x=(getWidth()-width)/2,y=(availableHeight-height)/2;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(Color.rgb(133,186,240));
            canvas.drawRoundRect(new RectF(x,y,x+width,y+height),width/2,width/2,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(184,199,213));paint.setTextSize(dp(13));paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("连接设备后显示实时画面",getWidth()/2f,getHeight()-dp(8),paint);
        }
    }
}
