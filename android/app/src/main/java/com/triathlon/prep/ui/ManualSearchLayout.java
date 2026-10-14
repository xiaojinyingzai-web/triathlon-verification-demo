package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.widget.*;

/** UI-only skeleton. No business Model, search, selection, save, or print implementation. */
abstract class ManualSearchLayout extends ScrollView {
    protected final EditText query;
    protected final Button back,search,confirm,reprint;
    protected final TextView mode,summary,status,resultSummary,detailText;
    protected final LinearLayout results;
    protected final LinearLayout detailPanel;
    ManualSearchLayout(Context context){
        super(context);setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));addView(panel,new ScrollView.LayoutParams(-1,-2));
        back=button("← 返回核验");add(panel,back,0);add(panel,text("护照人工核验",25,true),12);
        mode=text("待初始化",14,false);add(panel,mode,4);summary=text("正在读取当前名单",15,false);add(panel,summary,6);
        add(panel,text("仅本机当前有效名单。请选中人员，再核对护照与本人；此通道不执行自动人脸核验。",14,false),8);
        LinearLayout inputRow=new LinearLayout(context);inputRow.setOrientation(LinearLayout.HORIZONTAL);inputRow.setGravity(Gravity.CENTER_VERTICAL);
        query=new EditText(context);query.setSingleLine(true);query.setTextSize(18);query.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setHint("搜索姓名、比赛号码或护照号码");query.setContentDescription("姓名、比赛号码或护照号码，前导零原样保留");query.setFocusableInTouchMode(true);inputRow.addView(query,new LinearLayout.LayoutParams(0,dp(52),1));search=button("搜索");inputRow.addView(search,new LinearLayout.LayoutParams(dp(120),dp(52)));add(panel,inputRow,14);
        status=text("尚未搜索",15,false);add(panel,status,8);
        LinearLayout columns=new LinearLayout(context);columns.setOrientation(LinearLayout.HORIZONTAL);columns.setGravity(Gravity.TOP);
        LinearLayout list=card();add(list,text("搜索结果",20,true),0);resultSummary=text("请输入条件后搜索；多条结果请明确选择。",14,false);add(list,resultSummary,8);results=column();add(list,results,8);columns.addView(list,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout detail=card();detailPanel=detail;add(detail,text("选中人员与人工确认",20,true),0);detailText=text("尚未选中名单人员",17,false);add(detail,detailText,10);add(detail,text("工作人员核对护照与本人后，才可明确确认。保存失败不会打印，打印关闭时不发送。",14,false),12);
        confirm=button("确认人工核验并打印");reprint=button("明确补打小票");add(detail,confirm,14);add(detail,reprint,8);LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(0,-2,1);detailParams.leftMargin=dp(16);columns.addView(detail,detailParams);add(panel,columns,14);
        for(Button button:new Button[]{back,search,confirm,reprint})button.setEnabled(false);query.setEnabled(false);reprint.setVisibility(GONE);
        query.setOnEditorActionListener((view,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH&&search.isEnabled()){search.performClick();return true;}return false;});
        query.setOnFocusChangeListener((view,focused)->{if(focused)showKeyboard();});
    }
    protected void focusSearch(){if(query.isEnabled()){query.requestFocus();showKeyboard();}}
    private void showKeyboard(){query.post(()->{if(query.isEnabled()&&query.hasFocus()&&query.isAttachedToWindow()){InputMethodManager input=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);if(input!=null)input.showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);}});}
    protected int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    protected LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view;}
    protected LinearLayout card(){LinearLayout view=column();view.setPadding(dp(14),dp(14),dp(14),dp(14));view.setBackgroundColor(Color.WHITE);return view;}
    protected TextView text(String label,int size,boolean bold){TextView view=new TextView(getContext());view.setText(label);view.setTextSize(size);view.setTextColor(bold?0xff162638:0xff637487);if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
    protected Button button(String label){Button view=new Button(getContext());view.setText(label);view.setTextSize(16);view.setTextColor(0xff1769b7);view.setAllCaps(false);view.setMinimumHeight(dp(48));return view;}
    protected void add(LinearLayout parent,android.view.View view,int gap){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(gap);parent.addView(view,params);}
}
