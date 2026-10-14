package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.triathlon.prep.core.DeviceConfigDraft;
import java.util.*;

/** Public-parameter editor only. Host owns validation authority, persistence and rollback. */
public final class DeviceConfigView extends ScrollView {
    public enum State { LOADING, EMPTY, READY, SAVING, SAVED, FAILED, ROLLED_BACK }
    public interface Actions {
        void onDraftChanged(DeviceConfigDraft draft);
        void onSaveRequested(String draftToken,long revision);
        void onRollbackRequested(long revision);
        void onBack();
    }
    public static final class Model {
        public final DeviceConfigDraft current,draft;
        public final DeviceConfigDraft.Review review;
        public final State state;
        public final String draftToken,notice;
        public final long revision;
        public final boolean simulation,saveEnabled,rollbackEnabled,backEnabled;
        public Model(DeviceConfigDraft current,DeviceConfigDraft draft,DeviceConfigDraft.Review review,
                State state,String draftToken,long revision,boolean simulation,boolean saveEnabled,
                boolean rollbackEnabled,boolean backEnabled,String notice){
            if(current==null||draft==null||review==null||state==null||draftToken==null||notice==null||revision<0)
                throw new IllegalArgumentException("Config presentation fields required");
            if((state==State.LOADING||state==State.SAVING)&&(saveEnabled||rollbackEnabled)
                    ||saveEnabled&&(!review.validForSave||draftToken.isEmpty()))
                throw new IllegalArgumentException("Invalid config action permission");
            this.current=current;this.draft=draft;this.review=review;this.state=state;this.draftToken=draftToken;
            this.revision=revision;this.simulation=simulation;this.saveEnabled=saveEnabled;
            this.rollbackEnabled=rollbackEnabled;this.backEnabled=backEnabled;this.notice=notice;
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7,LINE=0xffdce4ec;
    private final Actions actions;
    private final TextView mode,status,currentSummary,diagnosis;
    private final Button save,rollback,back;
    private final EnumMap<DeviceConfigDraft.Field,View> inputs=new EnumMap<>(DeviceConfigDraft.Field.class);
    private Model model,pendingModel;
    private DeviceConfigDraft editing=DeviceConfigDraft.empty();
    private boolean rendering,awaitingDraft,pending;

    public DeviceConfigView(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("Actions required");this.actions=actions;
        setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));
        addView(panel,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        back=button("← 返回设备与设置");back.setOnClickListener(v->{if(model!=null&&back.isEnabled())actions.onBack();});add(panel,back,0);
        add(panel,text("设备配置准备",25,INK,true),16);
        mode=text("配置状态待同步",14,MUTED,false);add(panel,mode,6);
        add(panel,text("保存配置不会启用设备或在线服务。仅填写已确认的参数，留空可保存为草案。",16,INK,false),12);
        add(panel,text("凭据、许可与真实授权：本批未检查 / 未装载。设备尚未验证。",14,MUTED,false),6);
        LinearLayout overview=card();overview.addView(text("当前保存配置",19,INK,true));
        currentSummary=text("尚未读取",14,MUTED,false);add(overview,currentSummary,8);add(panel,overview,16);
        LinearLayout feedback=card();status=text("等待读取配置",17,INK,true);feedback.addView(status);
        diagnosis=text("",14,MUTED,false);add(feedback,diagnosis,8);
        LinearLayout controls=row();save=button("保存配置草案");rollback=button("恢复上一份配置");
        controls.addView(save,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1);rp.leftMargin=dp(12);controls.addView(rollback,rp);add(feedback,controls,12);add(panel,feedback,16);
        save.setOnClickListener(v->{if(model!=null&&save.isEnabled()){pending=true;pendingModel=model;refresh();actions.onSaveRequested(model.draftToken,model.revision);}});
        rollback.setOnClickListener(v->{if(model!=null&&rollback.isEnabled()){pending=true;pendingModel=model;refresh();actions.onRollbackRequested(model.revision);}});
        for(DeviceConfigDraft.Group group:DeviceConfigDraft.Group.values()){
            LinearLayout section=card();section.addView(text(groupLabel(group),20,INK,true));LinearLayout fieldsRow=null;int index=0;
            for(DeviceConfigDraft.Field field:DeviceConfigDraft.Field.values())if(field.group==group){
                if(index%2==0){fieldsRow=row();fieldsRow.setGravity(Gravity.TOP);add(section,fieldsRow,12);}
                LinearLayout fieldPanel=column();fieldPanel.addView(text(field.label,15,INK,false));
                View input=createInput(field);inputs.put(field,input);add(fieldPanel,input,5);
                LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1);if(index%2==1)fp.leftMargin=dp(20);fieldsRow.addView(fieldPanel,fp);index++;
            }
            if(index%2==1)fieldsRow.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));add(panel,section,16);
        }
        add(panel,text("参数范围用于检查输入格式，不代表厂家已确认或设备已可用。",13,MUTED,false),16);refresh();
    }
    private View createInput(DeviceConfigDraft.Field field){
        if(field.kind==DeviceConfigDraft.Kind.CHOICE){
            Spinner spinner=new Spinner(getContext());List<String> labels=new ArrayList<>();labels.add("未确认");for(String choice:field.choices)labels.add(choiceLabel(choice));
            ArrayAdapter<String> adapter=new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);spinner.setMinimumHeight(dp(48));spinner.setContentDescription(field.label);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(position!=spinner.getSelectedItemPosition()||position==field.choices.indexOf(safeDisplay(field,editing.value(field)))+1)return;changed(field,position==0?"":field.choices.get(position-1));}});return spinner;
        }
        EditText input=new EditText(getContext());input.setTextSize(16);input.setSingleLine(true);input.setHint("未确认，留空");input.setContentDescription(field.label);input.setMinimumHeight(dp(48));
        input.setInputType(field.kind==DeviceConfigDraft.Kind.INTEGER?InputType.TYPE_CLASS_NUMBER:field.kind==DeviceConfigDraft.Kind.DECIMAL?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        InputFilter length=new InputFilter.LengthFilter(field.maxLength);
        if(field==DeviceConfigDraft.Field.FACE_ENDPOINT){
            input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
            InputFilter publicAddress=(source,start,end,dest,dstart,dend)->{for(int i=start;i<end;i++){char c=source.charAt(i);if(c=='@'||c=='?'||c=='#'||Character.isWhitespace(c)||Character.isISOControl(c))return "";}return null;};
            input.setFilters(new InputFilter[]{length,publicAddress});
        }else input.setFilters(new InputFilter[]{length});
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed(field,s.toString());}public void afterTextChanged(Editable e){}});return input;
    }
    private void changed(DeviceConfigDraft.Field field,String value){
        if(rendering||model==null||pending||busy()||editing.value(field).equals(value))return;
        editing=editing.with(field,value);awaitingDraft=true;refresh();actions.onDraftChanged(editing);
    }
    public void render(Model next){
        main();if(next==null)throw new IllegalArgumentException("Model required");
        if(pending&&next!=pendingModel){pending=false;pendingModel=null;}
        boolean accept=!awaitingDraft||next.draft.equals(editing);
        model=next;if(accept){editing=next.draft;awaitingDraft=false;rendering=true;
            try{for(Map.Entry<DeviceConfigDraft.Field,View> entry:inputs.entrySet()){
                DeviceConfigDraft.Field f=entry.getKey();View input=entry.getValue();String raw=safeDisplay(f,editing.value(f));
                if(input instanceof EditText){EditText edit=(EditText)input;if(!edit.getText().toString().equals(raw))edit.setText(raw);}
                else {Spinner spinner=(Spinner)input;int position=f.choices.indexOf(raw)+1;if(spinner.getSelectedItemPosition()!=position)spinner.setSelection(position);}
            }}finally{rendering=false;}}
        mode.setText(next.simulation?"TEST虚构测试 · 配置准备":"配置准备 · 真实执行未启用");
        currentSummary.setText(summary(next.current));status.setText(stateLabel(next.state)+(next.notice.isEmpty()?"":"\n"+next.notice));
        StringBuilder info=new StringBuilder();if(awaitingDraft)info.append("输入已变化，等待重新校验。\n");
        for(String error:next.review.errors)info.append("需修正：").append(error).append('\n');
        if(!next.review.missing.isEmpty())info.append("待确认：").append(android.text.TextUtils.join("、",next.review.missing)).append('\n');
        info.append(next.review.parametersComplete?"参数格式完整；设备、凭据及授权仍未验证。":"可保存合法的部分草案；缺项不会自动补默认值。");diagnosis.setText(info.toString());refresh();
    }
    private void refresh(){
        boolean idle=model!=null&&!busy()&&!pending;
        save.setEnabled(idle&&!awaitingDraft&&model.saveEnabled);rollback.setEnabled(idle&&!awaitingDraft&&model.rollbackEnabled);
        back.setEnabled(model!=null&&model.backEnabled&&!pending);
        for(View input:inputs.values())input.setEnabled(idle);
    }
    private boolean busy(){return model!=null&&(model.state==State.LOADING||model.state==State.SAVING);}
    private String safeDisplay(DeviceConfigDraft.Field field,String raw){
        if(field==DeviceConfigDraft.Field.FACE_ENDPOINT&&!raw.isEmpty()&&!DeviceConfigDraft.empty().with(field,raw).review().errors.isEmpty())return "";return raw;
    }
    private String summary(DeviceConfigDraft draft){StringBuilder out=new StringBuilder();for(DeviceConfigDraft.Group group:DeviceConfigDraft.Group.values()){int filled=0,total=0;for(DeviceConfigDraft.Field f:DeviceConfigDraft.Field.values())if(f.group==group){total++;if(!draft.value(f).trim().isEmpty())filled++;}if(out.length()>0)out.append("  ·  ");out.append(groupLabel(group)).append(' ').append(filled).append('/').append(total);}return out.toString()+"\n保存的参数不会启动真实执行。";}
    private static String choiceLabel(String choice){switch(choice){case "USB":return "USB联网读卡";case "SAM_USB":return "USB SAM模块";case "SAM_SERIALPORT":return "SAM串口读卡";case "SIGNED":return "有符号字节";case "UNSIGNED":return "无符号字节";case "LOWER":return "小写";case "UPPER":return "大写";case "AT_LEAST":return "达到或高于阈值";case "AT_MOST":return "达到或低于阈值";default:return choice+"°";}}
    private static String groupLabel(DeviceConfigDraft.Group group){switch(group){case DEVICE:return "设备信息";case READER:return "读卡器";case CAMERA:return "标准相机";case PRINTER:return "USB打印";case FACE:return "在线比对参数";case TIMING:return "超时与预览时间";default:return "图像上限";}}
    private static String stateLabel(State state){switch(state){case LOADING:return "正在读取配置";case EMPTY:return "尚未保存配置";case READY:return "配置草案待确认";case SAVING:return "正在保存配置，尚未启用设备";case SAVED:return "配置已保存，设备未验证";case FAILED:return "操作未完成，请检查提示";default:return "已恢复上一份配置，设备未验证";}}
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI main thread required");}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout result=new LinearLayout(getContext());result.setOrientation(LinearLayout.VERTICAL);return result;}
    private LinearLayout row(){LinearLayout result=column();result.setOrientation(LinearLayout.HORIZONTAL);result.setGravity(Gravity.CENTER_VERTICAL);return result;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable bg=new GradientDrawable();bg.setColor(0xffffffff);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),LINE);c.setBackground(bg);return c;}
    private Button button(String label){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextColor(BLUE);b.setMinimumHeight(dp(48));return b;}
    private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);p.topMargin=dp(gap);parent.addView(child,p);}
    @Override protected void onDetachedFromWindow(){super.onDetachedFromWindow();pending=false;pendingModel=null;awaitingDraft=false;model=null;editing=DeviceConfigDraft.empty();rendering=true;try{for(View input:inputs.values())if(input instanceof EditText)((EditText)input).setText("");else ((Spinner)input).setSelection(0);}finally{rendering=false;}refresh();}
}
