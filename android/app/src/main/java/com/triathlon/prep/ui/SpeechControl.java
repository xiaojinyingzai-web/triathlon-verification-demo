package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.triathlon.prep.speech.SpeechPort;
import com.triathlon.prep.speech.SpeechSettings;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Controlled speech settings. Host owns persistence, speech and lifecycle. */
public final class SpeechControl extends LinearLayout {
    public enum SaveStatus { LOADING, READY, SAVING, FAILED }
    public interface Actions {
        void onEnabledRequested(boolean enabled);
        void onVolumeRequested(int volumePercent);
        void onTextsSaveRequested(Map<SpeechSettings.Scene,String> drafts);
        void onPreviewRequested(SpeechSettings.Scene scene,String draft,int volumePercent);
    }
    public static final class Model {
        public final SpeechSettings confirmed;
        public final SaveStatus saveStatus;
        public final SpeechPort.Readiness readiness;
        public final SpeechPort.Progress progress;
        public final String notice;
        public final long revision;
        public final boolean canEdit,canPreview;
        public Model(SpeechSettings confirmed,SaveStatus saveStatus,SpeechPort.Readiness readiness,
                SpeechPort.Progress progress,String notice,long revision,boolean canEdit,boolean canPreview){
            if(confirmed==null||saveStatus==null||readiness==null||progress==null||revision<0)
                throw new IllegalArgumentException("speech model required");
            if(canEdit&&(saveStatus==SaveStatus.LOADING||saveStatus==SaveStatus.SAVING))
                throw new IllegalArgumentException("pending speech settings cannot edit");
            if(canPreview&&readiness!=SpeechPort.Readiness.READY)
                throw new IllegalArgumentException("preview requires ready speech engine");
            this.confirmed=confirmed;this.saveStatus=saveStatus;this.readiness=readiness;
            this.progress=progress;this.notice=notice;this.revision=revision;
            this.canEdit=canEdit;this.canPreview=canPreview;
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7;
    private final CheckBox enabled;
    private final SeekBar volume;
    private final TextView engine,state,volumeLabel,previewState,testNotice;
    private final EnumMap<SpeechSettings.Scene,EditText> inputs=new EnumMap<>(SpeechSettings.Scene.class);
    private final EnumMap<SpeechSettings.Scene,Button> previews=new EnumMap<>(SpeechSettings.Scene.class);
    private final Button save;
    private Model model;
    private boolean binding,requestPending,dragging;

    public SpeechControl(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("speech actions required");
        setOrientation(VERTICAL);addView(text("语音播报",20,INK,true));
        engine=text("语音状态尚未读取",15,MUTED,false);add(engine,8);
        add(text("语音不可用不影响核验。开关控制自动播报，试听为手动操作。",14,MUTED,false),8);
        testNotice=text("",14,MUTED,false);add(testNotice,6);
        enabled=new CheckBox(context);enabled.setText("自动语音播报");enabled.setTextSize(16);
        enabled.setTextColor(INK);enabled.setMinimumHeight(dp(48));add(enabled,10);
        volumeLabel=text("应用语音音量：70%",16,INK,false);add(volumeLabel,10);
        volume=new SeekBar(context);volume.setMax(100);volume.setProgress(70);volume.setMinimumHeight(dp(48));
        volume.setContentDescription("应用语音音量，百分之0到100");add(volume,4);
        add(text("仅调整本应用语音；0%为静音。",14,MUTED,false),4);
        previewState=text("试听未开始",14,MUTED,false);
        String[] labels={"等待刷证","正对相机","核验通过","核验不通过","名单未匹配"};
        int index=0;
        for(SpeechSettings.Scene scene:SpeechSettings.Scene.values()){
            String label=labels[index++];add(text(label,16,INK,true),18);
            EditText input=new EditText(context);input.setTextSize(16);input.setTextColor(INK);
            input.setSingleLine(true);input.setHint(SpeechSettings.defaults().text(scene));
            input.setContentDescription(label+"播报文字，最多100个字符，留空使用默认");
            inputs.put(scene,input);add(input,6);
            add(text("留空默认："+SpeechSettings.defaults().text(scene),13,MUTED,false),4);
            Button preview=button("试听“"+label+"”");previews.put(scene,preview);add(preview,6);
            preview.setOnClickListener(v->{Model m=model;if(m==null||!m.canPreview||requestPending||volume.getProgress()==0)return;
                String raw=input.getText().toString();if(!validate(input,scene,raw))return;
                previewState.setText("正在请求试听；不会保存文字或启动核验");
                actions.onPreviewRequested(scene,raw,volume.getProgress());
            });
        }
        save=button("保存五项播报文字");add(save,18);
        state=text("语音设置尚未读取",14,MUTED,false);add(state,8);
        add(previewState,8);
        add(text("每项最多100个字符；试听当前文字，不自动保存。",13,MUTED,false),6);
        enabled.setOnCheckedChangeListener((b,checked)->{if(binding)return;Model m=model;
            if(m==null||!m.canEdit||requestPending)return;beginRequest();actions.onEnabledRequested(checked);
        });
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){updateVolumeLabel();refreshPreviewButtons();}
            public void onStartTrackingTouch(SeekBar bar){dragging=true;}
            public void onStopTrackingTouch(SeekBar bar){dragging=false;Model m=model;
                if(m==null||!m.canEdit||requestPending||bar.getProgress()==m.confirmed.volumePercent)return;
                beginRequest();actions.onVolumeRequested(bar.getProgress());
            }
        });
        save.setOnClickListener(v->{Model m=model;if(m==null||!m.canEdit||requestPending)return;
            EnumMap<SpeechSettings.Scene,String> drafts=new EnumMap<>(SpeechSettings.Scene.class);
            for(SpeechSettings.Scene scene:SpeechSettings.Scene.values()){
                EditText input=inputs.get(scene);String raw=input.getText().toString();
                if(!validate(input,scene,raw))return;drafts.put(scene,raw);
            }
            beginRequest();actions.onTextsSaveRequested(Collections.unmodifiableMap(new EnumMap<>(drafts)));
        });
        refreshControls();
    }

    /** Tickers retain drafts. Only actual confirmed text changes replace the associated field. */
    public void render(Model m){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("speech render on main thread");
        if(m==null)throw new IllegalArgumentException("speech model required");
        Model previous=model;if(previous!=null&&m.revision<previous.revision)return;
        model=m;binding=true;
        try{
            enabled.setChecked(m.confirmed.enabled);
            if(!dragging)volume.setProgress(m.confirmed.volumePercent);
            for(SpeechSettings.Scene scene:SpeechSettings.Scene.values()){
                String confirmed=m.confirmed.texts().get(scene);
                if(previous==null||!confirmed.equals(previous.confirmed.texts().get(scene))){
                    inputs.get(scene).setText(confirmed);inputs.get(scene).setError(null);
                }
            }
        }finally{binding=false;}
        requestPending=m.saveStatus==SaveStatus.LOADING||m.saveStatus==SaveStatus.SAVING;
        engine.setText(engineLabel(m.readiness));
        engine.setTextColor(m.readiness==SpeechPort.Readiness.READY?BLUE:MUTED);
        String label;
        switch(m.saveStatus){case LOADING:label="语音设置尚未读取";break;
            case SAVING:label="正在保存；已确认设置保持原值";break;
            case FAILED:label="语音设置未保存；已确认设置保持原值，编辑文字已保留";break;
            default:label="已确认：自动播报"+(m.confirmed.enabled?"开启":"关闭")+"，音量"+m.confirmed.volumePercent+"%";break;
        }
        if(m.notice!=null&&!m.notice.trim().isEmpty())label+="\n"+m.notice;
        state.setText(label);state.setTextColor(m.saveStatus==SaveStatus.FAILED?0xffa13730:MUTED);
        previewState.setText(progressLabel(m.progress));updateVolumeLabel();refreshControls();
    }
    /** Host calls alongside settings rendering; the prefix itself remains Host-owned. */
    public void setSimulation(boolean simulation){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("speech mode on main thread");
        testNotice.setText(simulation?"虚构测试：播报与试听带“模拟测试”前缀。":"只播报设置的提示文字，不自动朗读个人信息。");
    }
    private boolean validate(EditText input,SpeechSettings.Scene scene,String raw){
        try{EnumMap<SpeechSettings.Scene,String> candidate=new EnumMap<>(SpeechSettings.Scene.class);
            candidate.putAll(SpeechSettings.defaults().texts());candidate.put(scene,raw);
            new SpeechSettings(false,volume.getProgress(),candidate);input.setError(null);return true;
        }catch(IllegalArgumentException error){input.setError("最多100个字符，不能含换行、控制字符或无效字符");input.requestFocus();return false;}
    }
    private void beginRequest(){requestPending=true;state.setText("正在提交语音设置");refreshControls();}
    private void refreshControls(){boolean edit=model!=null&&model.canEdit&&!requestPending;
        enabled.setEnabled(edit);volume.setEnabled(edit);save.setEnabled(edit);save.setAlpha(edit?1f:.45f);
        for(EditText input:inputs.values())input.setEnabled(edit);refreshPreviewButtons();
    }
    private void refreshPreviewButtons(){boolean available=model!=null&&model.canPreview&&!requestPending&&volume.getProgress()>0;
        for(Button button:previews.values()){button.setEnabled(available);button.setAlpha(available?1f:.45f);}
    }
    private void updateVolumeLabel(){volumeLabel.setText("应用语音音量："+volume.getProgress()+"%"+(volume.getProgress()==0?"（静音，无法试听）":""));}
    private String engineLabel(SpeechPort.Readiness readiness){switch(readiness){
        case INITIALIZING:return "语音正在初始化";case READY:return "本地中文语音已就绪";
        case NO_ENGINE:return "语音未就绪：未找到可用的本地引擎";
        case NO_CHINESE:return "语音未就绪：本地中文语音不可用";
        case FAILED:return "语音初始化失败，核验仍可继续";default:return "语音已关闭";
    }}
    private String progressLabel(SpeechPort.Progress progress){switch(progress){
        case QUEUED:return "语音请求已提交，等待引擎处理";
        case STARTED:return "引擎正在处理语音；实际声音待确认";
        case DONE:return "引擎回调已完成；不代表扬声器已发声";
        case FAILED:return "语音处理失败，不影响核验";
        case CANCELLED:return "语音请求已取消";
        case MUTED:return "应用语音已静音";default:return "当前没有语音请求";
    }}
    private TextView text(String label,int size,int color,boolean bold){TextView view=new TextView(getContext());view.setText(label);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
    private Button button(String label){Button view=new Button(getContext());view.setText(label);view.setTextSize(16);view.setTextColor(BLUE);view.setAllCaps(false);view.setMinimumHeight(dp(48));return view;}
    private void add(android.view.View view,int gap){LayoutParams params=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);params.topMargin=dp(gap);addView(view,params);}
    private int dp(int size){return Math.round(size*getResources().getDisplayMetrics().density);}
}
