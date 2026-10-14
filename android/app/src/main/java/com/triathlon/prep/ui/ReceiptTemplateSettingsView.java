package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.triathlon.prep.core.CheckInReceipt;
import com.triathlon.prep.core.RaceRoster;
import com.triathlon.prep.core.ReceiptTemplate;
import java.util.*;

/** Draft editor and sample preview only. Host owns atomic saving and real receipt eligibility. */
public final class ReceiptTemplateSettingsView extends ScrollView {
    public enum State { READY, SAVING, SAVED, FAILED }
    public interface Actions { void onSave(ReceiptTemplate draft);void onCancel();void onBack(); }
    public static final class Model {
        public final ReceiptTemplate confirmed;public final State state;public final String notice;
        public final long revision;public final boolean saveEnabled,backEnabled;
        public Model(ReceiptTemplate confirmed,State state,String notice,long revision,boolean saveEnabled,boolean backEnabled){
            if(confirmed==null||state==null||notice==null||revision<0)throw new IllegalArgumentException("template model required");
            if(state==State.SAVING&&saveEnabled)throw new IllegalArgumentException("saving cannot submit again");
            this.confirmed=confirmed;this.state=state;this.notice=notice;this.revision=revision;this.saveEnabled=saveEnabled;this.backEnabled=backEnabled;
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7;
    private final Actions actions;private final TextView status;
    private final EnumMap<ReceiptTemplate.TextKey,EditText> texts=new EnumMap<>(ReceiptTemplate.TextKey.class);
    private final EnumMap<CheckInReceipt.Item,EditText> itemLabels=new EnumMap<>(CheckInReceipt.Item.class);
    private final EnumMap<CheckInReceipt.Item,CheckBox> itemVisible=new EnumMap<>(CheckInReceipt.Item.class);
    private final EnumMap<CheckInReceipt.Item,String> labelDrafts=new EnumMap<>(CheckInReceipt.Item.class);
    private final EnumMap<CheckInReceipt.Item,Boolean> visibleDrafts=new EnumMap<>(CheckInReceipt.Item.class);
    private final EnumMap<ReceiptTemplate.Section,ReceiptTemplate.Style> styles=new EnumMap<>(ReceiptTemplate.Section.class);
    private final List<CheckInReceipt.Item> order=new ArrayList<>();private final List<View> editControls=new ArrayList<>();
    private final List<Button> moveButtons=new ArrayList<>();
    private final LinearLayout[] sections=new LinearLayout[4];
    private final List<Button> tabs=new ArrayList<>();
    private final LinearLayout toolbar;
    private long pendingRevision;
    private State pendingState;
    private String pendingNotice;
    private final LinearLayout items;private final Spinner section,size,align,spacing,swim;private final CheckBox bold;
    private final Button save,preview,back,cancel;private final ImageView image;private Model model;private boolean binding,pending;
    public ReceiptTemplateSettingsView(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("template actions required");this.actions=actions;setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));addView(panel,new ScrollView.LayoutParams(-1,-2));
        toolbar=column();toolbar.setBackgroundColor(0xfff5f7fa);toolbar.setElevation(dp(8));add(panel,toolbar,0);
        back=button("← 返回设置");add(toolbar,back,0);add(toolbar,text("小票模板设置",25,true),8);
        add(toolbar,text("选手资料和勾选由名单绑定；修改预览不保存。",14,false),4);
        status=text("模板设置尚未读取",15,false);add(toolbar,status,6);
        LinearLayout actionsRow=new LinearLayout(context);actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        save=button("保存小票模板");cancel=button("取消修改");preview=button("修改预览");
        for(Button action:Arrays.asList(save,cancel,preview))actionsRow.addView(action,new LinearLayout.LayoutParams(0,dp(48),1));add(toolbar,actionsRow,6);
        LinearLayout navigation=new LinearLayout(context);navigation.setOrientation(LinearLayout.HORIZONTAL);String[] tabNames={"文字和时间","事项","版式","草稿预览"};
        for(int tab=0;tab<tabNames.length;tab++){final int index=tab;Button button=button(tabNames[tab]);tabs.add(button);navigation.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));button.setOnClickListener(v->showSection(index));}add(toolbar,navigation,6);
        LinearLayout content=card();add(content,text("文字与深水测试时间",20,true),0);
        String[] names={"赛事名称（留空沿用名单）","副标题","姓名标签","团体标签","组别标签","比赛号码标签","日期标签","深水测试日期","时间标签","深水测试时间","时间块提醒（可留空）","祝福语（可留空）"};int i=0;
        ReceiptTemplate.TextKey[] textOrder={ReceiptTemplate.TextKey.EVENT_HEADING,ReceiptTemplate.TextKey.SUBTITLE,ReceiptTemplate.TextKey.DATE_VALUE,ReceiptTemplate.TextKey.TIME_VALUE,ReceiptTemplate.TextKey.DATE_LABEL,ReceiptTemplate.TextKey.TIME_LABEL,ReceiptTemplate.TextKey.NAME_LABEL,ReceiptTemplate.TextKey.TEAM_LABEL,ReceiptTemplate.TextKey.GROUP_LABEL,ReceiptTemplate.TextKey.BIB_LABEL,ReceiptTemplate.TextKey.REMINDER,ReceiptTemplate.TextKey.WISH};
        for(i=0;i<textOrder.length;i+=2){LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);
            for(int cell=0;cell<2;cell++){ReceiptTemplate.TextKey key=textOrder[i+cell];LinearLayout field=column();add(field,text(names[key.ordinal()],15,true),0);EditText input=new EditText(context);input.setTextSize(16);input.setSingleLine(true);input.setTextColor(INK);input.setContentDescription(names[key.ordinal()]);texts.put(key,input);editControls.add(input);add(field,input,4);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);if(cell==1)params.leftMargin=dp(16);row.addView(field,params);}add(content,row,12);
        }sections[0]=content;add(panel,content,12);
        LinearLayout itemCard=card();add(itemCard,text("事项名称、顺序与显示",20,true),0);add(itemCard,text("改名或移动不会改变勾选含义；深水时间随绑定事项显示。",14,false),6);items=column();add(itemCard,items,10);sections[1]=itemCard;add(panel,itemCard,12);
        LinearLayout format=card();add(format,text("版式",20,true),0);
        section=spinner(new String[]{"赛事标题","副标题","个人字段","比赛号码","事项","深水时间块","祝福语"});size=spinner(new String[]{"小","标准","大"});align=spinner(new String[]{"左对齐","居中","右对齐"});spacing=spinner(new String[]{"紧凑","标准","宽松"});bold=new CheckBox(context);bold.setText("加粗");bold.setTextSize(16);
        add(format,text("调整区域",15,true),10);add(format,section,4);add(format,text("字号",15,true),10);add(format,size,4);add(format,bold,4);add(format,text("对齐",15,true),10);add(format,align,4);add(format,text("行距",15,true),10);add(format,spacing,4);
        editControls.addAll(Arrays.asList(section,size,align,spacing,bold));sections[2]=format;add(panel,format,12);
        LinearLayout sample=card();add(sample,text("模板测试预览",20,true),0);add(sample,text("虚构资料，仅预览草稿，不保存、不启动核验或打印。",14,false),6);
        swim=spinner(new String[]{"不打勾 · 显示时间","打勾 · 隐藏时间","未知 · 纸外待确认"});add(sample,swim,10);
        image=new ImageView(context);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("当前草稿小票，虚构模板测试预览");LinearLayout.LayoutParams picture=new LinearLayout.LayoutParams(dp(384),-2);picture.gravity=Gravity.CENTER_HORIZONTAL;picture.topMargin=dp(12);sample.addView(image,picture);sections[3]=sample;add(panel,sample,12);
        add(panel,text("预览为同源图像纸面；TEST送印图仍会附加非有效凭条标识。实际出纸待设备确认。",13,false),12);
        section.setOnItemSelectedListener(selection(()->loadStyle()));AdapterView.OnItemSelectedListener formatListener=selection(()->storeStyle());size.setOnItemSelectedListener(formatListener);align.setOnItemSelectedListener(formatListener);spacing.setOnItemSelectedListener(formatListener);bold.setOnCheckedChangeListener((b,v)->{if(!binding)storeStyle();});
        preview.setOnClickListener(v->{if(model==null||pending||model.state==State.SAVING)return;try{ReceiptTemplate draft=draft();RaceRoster.SwimTest state=swim.getSelectedItemPosition()==0?RaceRoster.SwimTest.MARKED:swim.getSelectedItemPosition()==1?RaceRoster.SwimTest.EXPLICIT_BLANK:RaceRoster.SwimTest.UNKNOWN;
            CheckInReceipt.Document document=CheckInReceipt.sample(draft,state);Bitmap bitmap=ReceiptBitmapRenderer.paperBitmap(new ReceiptBitmapRenderer().render(document,new CheckInReceipt.Layout(384,12,24)));image.setImageBitmap(bitmap);showSection(3);
            status.setText(document.unknownDeepWater?"草稿预览已更新；深水测试信息待确认。未保存。":"草稿预览已更新，尚未保存。");status.setTextColor(MUTED);
        }catch(RuntimeException error){error();}});
        save.setOnClickListener(v->{if(model==null||pending||!model.saveEnabled||model.state==State.SAVING)return;try{ReceiptTemplate draft=draft();pending=true;pendingRevision=model.revision;pendingState=model.state;pendingNotice=model.notice;refresh();status.setText("正在提交小票模板，等待确认");actions.onSave(draft);}catch(IllegalArgumentException invalid){error();}});
        back.setOnClickListener(v->{if(model!=null&&model.backEnabled&&!pending){loadDraft(model.confirmed);image.setImageDrawable(null);actions.onBack();}});
        cancel.setOnClickListener(v->{if(model!=null&&model.backEnabled&&!pending){loadDraft(model.confirmed);image.setImageDrawable(null);actions.onCancel();}});showSection(0);refresh();
    }
    public void render(Model m){main();if(m==null)throw new IllegalArgumentException("template model required");Model old=model;if(old!=null&&m.revision<old.revision)return;
        model=m;if(pending&&(m.state==State.SAVING||m.revision>pendingRevision||m.state!=pendingState||!m.notice.equals(pendingNotice)))pending=false;
        if(old==null||(m.revision!=old.revision&&m.state!=State.SAVING&&m.state!=State.FAILED)){loadDraft(m.confirmed);image.setImageDrawable(null);}
        status.setText(pending?"正在提交小票模板，等待确认":(m.state==State.SAVING?"正在保存模板":m.state==State.FAILED?"模板未保存，编辑草稿已保留":m.state==State.SAVED?"模板已保存":"当前模板已读取")+(m.notice.isEmpty()?"":"\n"+m.notice));status.setTextColor(m.state==State.FAILED?0xffa13730:MUTED);refresh();}
    private void loadDraft(ReceiptTemplate template){binding=true;try{for(ReceiptTemplate.TextKey key:ReceiptTemplate.TextKey.values())texts.get(key).setText(template.text(key));styles.clear();styles.putAll(template.styles);order.clear();labelDrafts.clear();visibleDrafts.clear();
        for(ReceiptTemplate.ItemSpec spec:template.items){order.add(spec.id);labelDrafts.put(spec.id,spec.label);visibleDrafts.put(spec.id,spec.visible);}rebuildItems();}finally{binding=false;}loadStyle();}
    private void rememberItems(){for(CheckInReceipt.Item id:itemLabels.keySet()){labelDrafts.put(id,itemLabels.get(id).getText().toString());visibleDrafts.put(id,itemVisible.get(id).isChecked());}}
    private void rebuildItems(){items.removeAllViews();moveButtons.clear();editControls.removeAll(itemLabels.values());editControls.removeAll(itemVisible.values());itemLabels.clear();itemVisible.clear();
        for(CheckInReceipt.Item id:order){LinearLayout box=column();add(box,text(itemName(id)+"（绑定事项，不可修改）",14,true),6);LinearLayout controls=new LinearLayout(getContext());controls.setOrientation(LinearLayout.HORIZONTAL);controls.setGravity(Gravity.CENTER_VERTICAL);
            EditText label=new EditText(getContext());label.setTextSize(16);label.setSingleLine(true);label.setText(labelDrafts.get(id));label.setContentDescription(itemName(id)+"显示名称");itemLabels.put(id,label);editControls.add(label);controls.addView(label,new LinearLayout.LayoutParams(0,dp(48),1));
            CheckBox visible=new CheckBox(getContext());visible.setText("显示");visible.setContentDescription(itemName(id)+"显示开关");visible.setChecked(visibleDrafts.get(id));itemVisible.put(id,visible);editControls.add(visible);controls.addView(visible,new LinearLayout.LayoutParams(dp(90),dp(48)));Button up=button("上移"),down=button("下移");controls.addView(up,new LinearLayout.LayoutParams(dp(90),dp(48)));controls.addView(down,new LinearLayout.LayoutParams(dp(90),dp(48)));moveButtons.add(up);moveButtons.add(down);add(box,controls,4);up.setOnClickListener(v->move(id,-1));down.setOnClickListener(v->move(id,1));items.addView(box);
        }refresh();}
    private void move(CheckInReceipt.Item id,int delta){if(model==null||pending||!model.saveEnabled)return;int from=order.indexOf(id),to=from+delta;if(to<0||to>=order.size())return;rememberItems();Collections.swap(order,from,to);rebuildItems();}
    private ReceiptTemplate draft(){rememberItems();storeStyle();EnumMap<ReceiptTemplate.TextKey,String> values=new EnumMap<>(ReceiptTemplate.TextKey.class);for(ReceiptTemplate.TextKey key:texts.keySet())values.put(key,texts.get(key).getText().toString());List<ReceiptTemplate.ItemSpec> specs=new ArrayList<>();for(CheckInReceipt.Item id:order)specs.add(new ReceiptTemplate.ItemSpec(id,labelDrafts.get(id),visibleDrafts.get(id)));return new ReceiptTemplate(values,specs,styles);}
    private void loadStyle(){if(styles.isEmpty())return;binding=true;try{ReceiptTemplate.Style style=styles.get(ReceiptTemplate.Section.values()[section.getSelectedItemPosition()]);size.setSelection(style.size.ordinal());align.setSelection(style.align.ordinal());spacing.setSelection(style.spacing.ordinal());bold.setChecked(style.bold);}finally{binding=false;}}
    private void storeStyle(){if(binding||styles.isEmpty())return;styles.put(ReceiptTemplate.Section.values()[section.getSelectedItemPosition()],new ReceiptTemplate.Style(ReceiptTemplate.FontSize.values()[size.getSelectedItemPosition()],bold.isChecked(),ReceiptTemplate.Align.values()[align.getSelectedItemPosition()],ReceiptTemplate.Spacing.values()[spacing.getSelectedItemPosition()]));}
    private void refresh(){boolean edit=model!=null&&model.saveEnabled&&!pending&&model.state!=State.SAVING;for(View control:editControls)control.setEnabled(edit);for(int i=0;i<moveButtons.size();i++)moveButtons.get(i).setEnabled(edit&&(i%2==0?i>0:i<moveButtons.size()-1));save.setEnabled(edit);preview.setEnabled(edit);swim.setEnabled(edit);back.setEnabled(model!=null&&model.backEnabled&&!pending);cancel.setEnabled(model!=null&&model.backEnabled&&!pending);}
    private void error(){status.setText("模板内容无法使用，请检查必填名称、文字长度和非法字符。草稿未保存。");status.setTextColor(0xffa13730);}
    private void showSection(int index){for(int i=0;i<sections.length;i++){sections[i].setVisibility(i==index?View.VISIBLE:View.GONE);tabs.get(i).setSelected(i==index);tabs.get(i).setTextColor(i==index?Color.WHITE:BLUE);tabs.get(i).setBackgroundColor(i==index?BLUE:Color.WHITE);}scrollTo(0,0);}
    private AdapterView.OnItemSelectedListener selection(Runnable action){return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(!binding)action.run();}public void onNothingSelected(AdapterView<?> parent){}};}
    private Spinner spinner(String[] labels){Spinner view=new Spinner(getContext());view.setAdapter(new ArrayAdapter<String>(getContext(),android.R.layout.simple_spinner_dropdown_item,labels));view.setMinimumHeight(dp(48));return view;}
    private String itemName(CheckInReceipt.Item id){switch(id){case IDENTITY:return "身份核验";case DECLARATION:return "签署声明书";case SWIMMING:return "深水测试";case INSURANCE:return "保险核验";default:return "赛事包发放";}}
    private LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout card(){LinearLayout view=column();view.setPadding(dp(14),dp(14),dp(14),dp(14));view.setBackgroundColor(Color.WHITE);return view;}
    private TextView text(String s,int size,boolean strong){TextView view=new TextView(getContext());view.setText(s);view.setTextSize(size);view.setTextColor(strong?INK:MUTED);if(strong)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
    private Button button(String label){Button view=new Button(getContext());view.setText(label);view.setTextColor(BLUE);view.setTextSize(16);view.setAllCaps(false);view.setMinimumHeight(dp(48));return view;}
    private void add(LinearLayout parent,View view,int gap){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(gap);parent.addView(view,params);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("template UI on main thread");}
    @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec);if(width>0){LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)image.getLayoutParams();int target=Math.max(1,Math.min(dp(384),width-dp(64)));if(params.width!=target){params.width=target;image.setLayoutParams(params);}}super.onMeasure(widthSpec,heightSpec);}
    @Override protected void onDetachedFromWindow(){image.setImageDrawable(null);super.onDetachedFromWindow();}
    @Override protected void onScrollChanged(int left,int top,int oldLeft,int oldTop){super.onScrollChanged(left,top,oldLeft,oldTop);if(toolbar!=null)toolbar.setTranslationY(top);}
}
