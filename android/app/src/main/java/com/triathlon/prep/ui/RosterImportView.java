package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure presentation. Host owns file selection, validation, candidate lifetime and persistence. */
public final class RosterImportView extends ScrollView {
    public enum State { IDLE, READING, REVIEW, INVALID, SAVING, SAVED, ERROR }
    public enum CurrentStatus { NONE, VALID, CORRUPT }
    public enum Field { FILE, EVENT, GROUP, BIB, NAME, ID_NUMBER, TEAM, ROW }
    public enum ErrorCode {
        UNSUPPORTED_FILE, UNREADABLE_FILE, MISSING_COLUMN, REQUIRED, INVALID_ID_LENGTH,
        INVALID_ID_DATE, INVALID_ID_CHECKSUM, LOST_PRECISION, DUPLICATE_ID, DUPLICATE_BIB,
        AMBIGUOUS_CONTEXT, EMPTY_FILE, TOO_LARGE, INVALID_CELL, OTHER
    }
    public interface Actions {
        void onChooseFile(String eventName, String fallbackGroup);
        void onConfirmImport(String previewId);
        void onDiscardPreview(String previewId);
        void onErrorPageChanged(String previewId, int page);
        void onBack();
    }
    /** Display-only summary; labels must be safe display text, never identifiers or raw cells. */
    public static final class BatchSummary {
        public final String eventName, batchLabel, fileLabel;
        public final int totalRows, validRows, errorRows;
        public BatchSummary(String eventName, String batchLabel, String fileLabel,
                int totalRows, int validRows, int errorRows) {
            if(totalRows<0||validRows<0||errorRows<0||validRows>totalRows||errorRows>totalRows
                    ||(long)validRows+errorRows!=totalRows)
                throw new IllegalArgumentException("invalid row counts");
            this.eventName=eventName;this.batchLabel=batchLabel;this.fileLabel=fileLabel;
            this.totalRows=totalRows;this.validRows=validRows;this.errorRows=errorRows;
        }
    }
    /** Numeric source location and typed reason only: no original value or arbitrary error message. */
    public static final class RowError {
        public final int sheetNumber, rowNumber;
        public final Field field;
        public final ErrorCode code;
        public RowError(int sheetNumber, int rowNumber, Field field, ErrorCode code) {
            if(sheetNumber<0||rowNumber<0||field==null||code==null)
                throw new IllegalArgumentException("invalid error location/type");
            this.sheetNumber=sheetNumber;this.rowNumber=rowNumber;this.field=field;this.code=code;
        }
    }
    /** Errors are one bounded page. Page indexes are zero-based; source sheet/row numbers are one-based (0=not applicable). */
    public static final class Model {
        public final boolean simulation, chooseEnabled, confirmEnabled, discardEnabled, backEnabled;
        public final State state;
        public final CurrentStatus currentStatus;
        public final BatchSummary currentBatch, candidate;
        public final String previewId, eventName, fallbackGroup, statusMessage;
        public final List<RowError> errors;
        public final int errorPage, errorPageCount, errorCount;
        public Model(boolean simulation, State state, CurrentStatus currentStatus,
                BatchSummary currentBatch, BatchSummary candidate, String previewId,
                String eventName, String fallbackGroup, String statusMessage,
                List<RowError> errors, int errorPage, int errorPageCount, int errorCount,
                boolean chooseEnabled, boolean confirmEnabled, boolean discardEnabled, boolean backEnabled) {
            if(state==null||currentStatus==null||errors==null)
                throw new IllegalArgumentException("state/status/errors required");
            if((currentStatus==CurrentStatus.VALID)!=(currentBatch!=null))
                throw new IllegalArgumentException("current summary/status mismatch");
            if(previewId!=null&&previewId.trim().isEmpty())throw new IllegalArgumentException("empty preview id");
            if(candidate!=null&&previewId==null)throw new IllegalArgumentException("candidate id required");
            if(errorCount<0||errorPageCount<0||errorPage<0
                    ||(errorPageCount==0?errorPage!=0:errorPage>=errorPageCount)
                    ||(errorCount==0)!=(errorPageCount==0)||errors.size()>errorCount
                    ||errors.size()>50||(!errors.isEmpty()&&previewId==null))
                throw new IllegalArgumentException("invalid error page");
            List<RowError> copy=new ArrayList<>(errors);
            for(RowError e:copy)if(e==null)throw new IllegalArgumentException("null row error");
            boolean busy=state==State.READING||state==State.SAVING;
            if(busy&&(chooseEnabled||confirmEnabled||discardEnabled))
                throw new IllegalArgumentException("busy actions must be disabled");
            if(confirmEnabled&&(state!=State.REVIEW||candidate==null||previewId==null
                    ||candidate.totalRows==0||candidate.errorRows!=0||errorCount!=0))
                throw new IllegalArgumentException("invalid confirm state");
            if(discardEnabled&&previewId==null)throw new IllegalArgumentException("discard id required");
            this.simulation=simulation;this.state=state;this.currentStatus=currentStatus;
            this.currentBatch=currentBatch;this.candidate=candidate;this.previewId=previewId;
            this.eventName=eventName;this.fallbackGroup=fallbackGroup;this.statusMessage=statusMessage;
            this.errors=Collections.unmodifiableList(copy);this.errorPage=errorPage;
            this.errorPageCount=errorPageCount;this.errorCount=errorCount;
            this.chooseEnabled=chooseEnabled;this.confirmEnabled=confirmEnabled;
            this.discardEnabled=discardEnabled;this.backEnabled=backEnabled;
        }
    }

    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7,LINE=0xffdce4ec;
    private final Actions actions;
    private final TextView mode, currentText, candidateText, status, pageText, foot;
    private final EditText event, group;
    private final LinearLayout candidateCard, errorCard, errorRows;
    private final Button back, choose, confirm, discard, previous, next;
    private Model model;

    public RosterImportView(Context context, Actions actions) {
        super(context);if(actions==null)throw new IllegalArgumentException("actions required");this.actions=actions;
        setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));
        addView(panel,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        back=button("← 返回核验",false);panel.addView(back);
        LinearLayout header=row();LinearLayout heading=column();heading.addView(text("赛事名单",25,INK,true));
        add(heading,text("导入本场名单，用于身份证号精确匹配",14,MUTED,false),4);
        header.addView(heading,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        mode=text("待初始化",13,0xff825409,true);mode.setPadding(dp(9),dp(7),dp(9),dp(7));
        mode.setBackground(background(0xfffff0d6,0xfffff0d6,8));header.addView(mode);add(panel,header,14);
        LinearLayout active=card();active.addView(text("当前有效名单",20,INK,true));
        currentText=text("尚未读取名单",16,MUTED,false);add(active,currentText,10);add(panel,active,18);
        LinearLayout source=card();source.addView(text("选择 Excel 名单",20,INK,true));
        add(source,text("必填：比赛号码、姓名、证件号码、参赛组别；所属团体可选。证件号码请保存为文本，避免精度丢失。",15,MUTED,false),10);
        add(source,text("赛事名称",15,INK,true),14);event=input("明确填写本场赛事名称");add(source,event,6);
        add(source,text("默认参赛组别（表格无组别列时填写）",15,INK,true),12);
        group=input("有组别列时使用表格内容");add(source,group,6);
        add(source,text("赛事或组别也可来自模板列；不会自动猜测合并标题。",13,MUTED,false),8);
        choose=button("选择 Excel 文件",false);add(source,choose,14);add(panel,source,16);
        status=text("等待选择文件",16,BLUE,true);add(panel,status,16);
        candidateCard=card();candidateCard.addView(text("待导入批次",20,INK,true));candidateText=text("",16,INK,false);
        add(candidateCard,candidateText,10);add(candidateCard,text("仅校验文件不会更换当前名单；确认并保存成功后才生效。",14,MUTED,false),10);add(panel,candidateCard,14);
        errorCard=card();errorCard.addView(text("需要修正的问题",20,0xffa13730,true));
        add(errorCard,text("整批校验失败时保留原名单。请修正文件后重新选择。",14,MUTED,false),8);
        errorRows=column();add(errorCard,errorRows,10);LinearLayout paging=row();
        previous=button("上一页",false);next=button("下一页",false);pageText=text("",13,MUTED,false);pageText.setGravity(Gravity.CENTER);
        paging.addView(previous);paging.addView(pageText,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));paging.addView(next);add(errorCard,paging,12);add(panel,errorCard,14);
        confirm=button("确认导入并替换当前名单",true);add(panel,confirm,18);
        discard=button("取消本次导入",false);add(panel,discard,10);
        foot=text("名单导入不代表身份核验通过",12,MUTED,false);foot.setGravity(Gravity.CENTER);add(panel,foot,16);
        back.setOnClickListener(v->{Model m=model;if(m!=null&&m.backEnabled)this.actions.onBack();});
        choose.setOnClickListener(v->{Model m=model;if(m!=null&&m.chooseEnabled)this.actions.onChooseFile(event.getText().toString(),group.getText().toString());});
        confirm.setOnClickListener(v->{Model m=model;if(m!=null&&m.confirmEnabled)this.actions.onConfirmImport(m.previewId);});
        discard.setOnClickListener(v->{Model m=model;if(m!=null&&m.discardEnabled)this.actions.onDiscardPreview(m.previewId);});
        previous.setOnClickListener(v->changePage(-1));next.setOnClickListener(v->changePage(1));
        enable(back,false);enable(choose,false);enable(confirm,false);enable(discard,false);
        enable(previous,false);enable(next,false);event.setEnabled(false);group.setEnabled(false);
        candidateCard.setVisibility(View.GONE);errorCard.setVisibility(View.GONE);
    }

    /** Host must supply sanitized display labels/messages and discard stale async results before render. */
    public void render(Model m) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("model required");
        Model previousModel=model;
        boolean resetContext=previousModel==null||(previousModel.previewId!=null&&!same(previousModel.previewId,m.previewId));
        if(resetContext||!same(value(previousModel.eventName,""),value(m.eventName,"")))updateInput(event,m.eventName);
        if(resetContext||!same(value(previousModel.fallbackGroup,""),value(m.fallbackGroup,"")))updateInput(group,m.fallbackGroup);
        model=m;
        mode.setText(m.simulation?"虚构测试":"正式模式");
        currentText.setText(m.currentStatus==CurrentStatus.VALID?summary(m.currentBatch,false):
                m.currentStatus==CurrentStatus.CORRUPT?"名单读取异常，当前不能进行赛事核验。":"尚未导入名单，当前不能进行赛事核验。");
        event.setEnabled(m.chooseEnabled&&m.previewId==null);group.setEnabled(m.chooseEnabled&&m.previewId==null);
        enable(choose,m.chooseEnabled);enable(confirm,m.confirmEnabled);enable(discard,m.discardEnabled);enable(back,m.backEnabled);
        status.setText(stateLabel(m.state)+(empty(m.statusMessage)?"":"\n"+m.statusMessage));
        status.setTextColor(m.state==State.INVALID||m.state==State.ERROR?0xffa13730:m.state==State.SAVED?0xff236c47:BLUE);
        candidateCard.setVisibility(m.candidate==null?View.GONE:View.VISIBLE);
        candidateText.setText(m.candidate==null?"":summary(m.candidate,true));
        errorCard.setVisibility(m.errorCount==0?View.GONE:View.VISIBLE);errorRows.removeAllViews();
        for(RowError e:m.errors){String location=e.sheetNumber==0?"文件":"工作表 "+e.sheetNumber;
            if(e.rowNumber>0)location+=" · 第 "+e.rowNumber+" 行";
            TextView line=text(location+" · "+fieldLabel(e.field)+"\n"+errorLabel(e.code),15,INK,false);
            line.setPadding(0,dp(10),0,dp(10));errorRows.addView(line);}
        pageText.setText(m.errorCount+" 项问题 · "+(m.errorPageCount==0?0:m.errorPage+1)+" / "+m.errorPageCount);
        boolean pageable=m.previewId!=null&&m.state!=State.READING&&m.state!=State.SAVING;
        enable(previous,pageable&&m.errorPage>0);enable(next,pageable&&m.errorPage+1<m.errorPageCount);
        foot.setText(m.simulation?"虚构名单测试 · 仅验证导入与匹配流程\n名单匹配与本次人证一致都满足，才可产生赛事通过":"名单只用于本场参赛身份匹配\n名单匹配与本次人证一致都满足，才可产生赛事通过");
    }
    private void changePage(int delta){Model m=model;if(m==null||m.previewId==null||m.state==State.READING||m.state==State.SAVING)return;
        int page=m.errorPage+delta;if(page>=0&&page<m.errorPageCount)actions.onErrorPageChanged(m.previewId,page);}
    private String summary(BatchSummary b,boolean pending){return "赛事："+value(b.eventName,"—")+"\n批次："+value(b.batchLabel,"—")+
            (pending?"\n文件："+value(b.fileLabel,"—"):"")+"\n"+(pending?"总行数："+b.totalRows+"   有效："+b.validRows+"   错误行："+b.errorRows:"人数："+b.validRows);}
    private static String stateLabel(State s){switch(s){case READING:return "正在读取并校验名单…";case REVIEW:return "名单已校验，等待确认导入";case INVALID:return "名单校验未通过，未替换当前名单";case SAVING:return "正在保存名单…";case SAVED:return "名单导入成功";case ERROR:return "导入未完成，未替换当前名单";default:return "等待选择文件";}}
    private static String fieldLabel(Field f){switch(f){case FILE:return "文件";case EVENT:return "赛事";case GROUP:return "参赛组别";case BIB:return "比赛号码";case NAME:return "姓名";case ID_NUMBER:return "证件号码";case TEAM:return "所属团体";default:return "数据行";}}
    private static String errorLabel(ErrorCode c){switch(c){case UNSUPPORTED_FILE:return "文件格式不支持，请选择支持的 Excel 文件";case UNREADABLE_FILE:return "文件无法读取，请检查文件后重选";case MISSING_COLUMN:return "缺少必需的列";case REQUIRED:return "必填字段为空";case INVALID_ID_LENGTH:return "证件号码长度或字符格式无效";case INVALID_ID_DATE:return "证件号码中的出生日期无效";case INVALID_ID_CHECKSUM:return "证件号码校验码无效";case LOST_PRECISION:return "证件号码精度已丢失或存在科学计数，请从原始资料修正为文本";case DUPLICATE_ID:return "证件号码重复";case DUPLICATE_BIB:return "比赛号码重复";case AMBIGUOUS_CONTEXT:return "赛事或组别无法明确确定，请补充导入上下文";case EMPTY_FILE:return "名单没有有效数据行";case TOO_LARGE:return "文件或数据量超过支持范围";case INVALID_CELL:return "单元格类型或内容格式无效";default:return "数据无法使用，请核对模板后重选文件";}}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static String value(String s,String fallback){return empty(s)?fallback:s;}
    private static boolean same(String a,String b){return a==null?b==null:a.equals(b);}
    private static void updateInput(EditText input,String text){String desired=value(text,"");if(!desired.equals(input.getText().toString()))input.setText(desired);}
    private static void enable(Button button,boolean enabled){button.setEnabled(enabled);button.setAlpha(enabled?1f:.45f);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(14),dp(16),dp(14),dp(16));l.setBackground(background(Color.WHITE,LINE,12));return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private EditText input(String hint){EditText e=new EditText(getContext());e.setTextSize(16);e.setTextColor(INK);e.setHint(hint);e.setSingleLine(true);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(background(Color.WHITE,LINE,7));e.setContentDescription(hint);return e;}
    private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);p.topMargin=dp(gap);parent.addView(child,p);}
    private GradientDrawable background(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private Button button(String label,boolean primary){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(primary?Color.WHITE:BLUE);b.setMinimumHeight(dp(50));b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setBackground(background(primary?BLUE:Color.WHITE,primary?BLUE:LINE,9));return b;}
}
