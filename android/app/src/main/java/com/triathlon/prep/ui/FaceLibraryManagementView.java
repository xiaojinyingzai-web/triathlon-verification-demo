package com.triathlon.prep.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Safe library summaries only. Host owns features, keys, SAF, preflight and activation. */
public final class FaceLibraryManagementView extends ScrollView {
    public enum State { LOADING,READY,EXPORTING,CHOOSING_IMPORT,PREFLIGHTING,IMPORT_REVIEW,IMPORTING,CLEARING,FAILED }
    public enum LibraryState { ABSENT,EMPTY,LOADING,READY,DIRTY,CORRUPT,INCOMPATIBLE }
    public enum RegistrationStatus { ENROLLED,REVIEW_REQUIRED }
    public static final class PersonRow {
        public final String personKey,bib,name,group,notice;public final RegistrationStatus registrationStatus;
        public PersonRow(String personKey,String bib,String name,String group,RegistrationStatus registrationStatus,String notice){
            if(personKey==null||personKey.isEmpty()||bib==null||name==null||group==null||registrationStatus==null||notice==null)throw new IllegalArgumentException("Safe person row required");
            this.personKey=personKey;this.bib=bib;this.name=name;this.group=group;this.registrationStatus=registrationStatus;this.notice=notice;
        }
    }
    public static final class ImportPreview {
        public final String preflightToken,eventName,batchId,modelLabel,sourceRevision,compatibilityNotice;
        public final int personCount,replaceCount;
        public ImportPreview(String preflightToken,String eventName,String batchId,String modelLabel,String sourceRevision,int personCount,int replaceCount,String compatibilityNotice){
            if(preflightToken==null||preflightToken.isEmpty()||eventName==null||batchId==null||modelLabel==null||sourceRevision==null||personCount<0||replaceCount<0||compatibilityNotice==null)throw new IllegalArgumentException("Safe preflight summary required");
            this.preflightToken=preflightToken;this.eventName=eventName;this.batchId=batchId;this.modelLabel=modelLabel;this.sourceRevision=sourceRevision;this.personCount=personCount;this.replaceCount=replaceCount;this.compatibilityNotice=compatibilityNotice;
        }
    }
    public static final class Model {
        public final boolean simulation,countsKnown;public final String eventName,batchId,modelLabel,libraryRevision,notice,operationId,requestToken,previousBatchId;public final boolean restoreEnabled;
        public final LibraryState libraryState;public final int storedCount,readyCount,page,pageCount,totalPeople;
        public final State state;public final List<PersonRow> rows;public final ImportPreview preview;public final long revision;
        public final boolean exportEnabled,chooseImportEnabled,confirmImportEnabled,cancelPreviewEnabled,clearEnabled,refreshEnabled,backEnabled,pageEnabled;
        public Model(boolean simulation,String eventName,String batchId,String modelLabel,String libraryRevision,LibraryState libraryState,int storedCount,int readyCount,boolean countsKnown,State state,String notice,int page,int pageCount,int totalPeople,List<PersonRow> rows,ImportPreview preview,String operationId,String requestToken,long revision,boolean exportEnabled,boolean chooseImportEnabled,boolean confirmImportEnabled,boolean cancelPreviewEnabled,boolean clearEnabled,boolean refreshEnabled,boolean backEnabled,boolean pageEnabled){
            this(simulation,eventName,batchId,modelLabel,libraryRevision,libraryState,storedCount,readyCount,countsKnown,state,notice,page,pageCount,totalPeople,rows,preview,operationId,requestToken,revision,exportEnabled,chooseImportEnabled,confirmImportEnabled,cancelPreviewEnabled,clearEnabled,refreshEnabled,backEnabled,pageEnabled,"",false);
        }
        public Model(boolean simulation,String eventName,String batchId,String modelLabel,String libraryRevision,LibraryState libraryState,int storedCount,int readyCount,boolean countsKnown,State state,String notice,int page,int pageCount,int totalPeople,List<PersonRow> rows,ImportPreview preview,String operationId,String requestToken,long revision,boolean exportEnabled,boolean chooseImportEnabled,boolean confirmImportEnabled,boolean cancelPreviewEnabled,boolean clearEnabled,boolean refreshEnabled,boolean backEnabled,boolean pageEnabled,String previousBatchId,boolean restoreEnabled){
            if(previousBatchId==null||restoreEnabled&&(previousBatchId.isEmpty()||state!=State.READY||(libraryState!=LibraryState.READY&&libraryState!=LibraryState.EMPTY)||preview!=null))throw new IllegalArgumentException("Ready previous selection required for restore");this.previousBatchId=previousBatchId;this.restoreEnabled=restoreEnabled;
            if(eventName==null||batchId==null||modelLabel==null||libraryRevision==null||libraryState==null||storedCount<0||readyCount<0||readyCount>storedCount||state==null||notice==null||page<0||pageCount<1||totalPeople<0||page>=pageCount||rows==null||rows.size()>100||operationId==null||requestToken==null||revision<0)throw new IllegalArgumentException("Invalid library presentation");
            if(libraryState!=LibraryState.READY&&readyCount!=0||confirmImportEnabled&&(preview==null||state!=State.IMPORT_REVIEW)||exportEnabled&&(!countsKnown||libraryState!=LibraryState.READY||storedCount==0))throw new IllegalArgumentException("Invalid library permission");
            if(busy(state)&&(exportEnabled||chooseImportEnabled||confirmImportEnabled||clearEnabled||refreshEnabled||pageEnabled))throw new IllegalArgumentException("Busy actions must be disabled");
            List<PersonRow> copy=new ArrayList<>(rows);Set<String> keys=new HashSet<>();for(PersonRow r:copy)if(r==null||!keys.add(r.personKey))throw new IllegalArgumentException("Unique rows required");
            this.simulation=simulation;this.countsKnown=countsKnown;this.eventName=eventName;this.batchId=batchId;this.modelLabel=modelLabel;this.libraryRevision=libraryRevision;this.libraryState=libraryState;this.storedCount=storedCount;this.readyCount=readyCount;this.state=state;this.notice=notice;this.page=page;this.pageCount=pageCount;this.totalPeople=totalPeople;this.rows=Collections.unmodifiableList(copy);this.preview=preview;this.operationId=operationId;this.requestToken=requestToken;this.revision=revision;this.exportEnabled=exportEnabled;this.chooseImportEnabled=chooseImportEnabled;this.confirmImportEnabled=confirmImportEnabled;this.cancelPreviewEnabled=cancelPreviewEnabled;this.clearEnabled=clearEnabled;this.refreshEnabled=refreshEnabled;this.backEnabled=backEnabled;this.pageEnabled=pageEnabled;
        }
    }
    public interface Actions {
        void onRefresh(String operationId,String requestToken,long revision);
        void onExport(String operationId,String requestToken,long revision);
        void onChooseImport(String operationId,String requestToken,long revision);
        void onConfirmImport(String operationId,String preflightToken,String requestToken,long revision,boolean confirmed);
        void onCancelPreflight(String operationId,String preflightToken,String requestToken,long revision);
        void onClearLibrary(String operationId,String requestToken,long revision,boolean confirmed);
        void onPageRequested(int page,String operationId,String requestToken,long revision);
        void onBack(String operationId,String requestToken,long revision);
        default void onRestorePreviousLibrary(String operationId,String requestToken,long revision,boolean confirmed){}
    }
    private final Actions actions;private final UiActionTicket ticket=new UiActionTicket();private Model model;
    private final TextView mode,current,status,count,candidate;private final LinearLayout people,candidatePanel;
    private final Button back,refresh,export,choose,confirm,discard,clear,previous,next,restore;
    private AlertDialog dialog;private Model confirmationModel;private long confirmationGeneration;
    public FaceLibraryManagementView(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("Actions required");this.actions=actions;setFillViewport(true);setBackgroundColor(0xfff5f7fa);setSaveEnabled(false);setSaveFromParentEnabled(false);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));addView(panel,new ScrollView.LayoutParams(-1,-2));
        back=button("← 返回核验");back.setOnClickListener(v->dispatch(7));panel.addView(back);
        add(panel,text("人脸库管理",25,true),14);mode=text("等待模式同步",14,true);add(panel,mode,6);
        add(panel,text("只管理本场人脸库。清理不撤销报到；导入须预检并明确确认后才激活。",15,false),10);
        LinearLayout summary=card();summary.addView(text("当前赛事与活动库",20,true));current=text("等待库状态",16,false);add(summary,current,10);status=text("",16,true);add(summary,status,12);add(panel,summary,16);
        LinearLayout tools=new LinearLayout(context);tools.setGravity(Gravity.CENTER_VERTICAL);refresh=button("刷新库状态");export=button("加密导出");choose=button("选择加密库预检");clear=button("清理本场库");restore=button("恢复上一库选择");for(Button b:new Button[]{refresh,export,choose,clear,restore})tools.addView(b,new LinearLayout.LayoutParams(0,-2,1));add(panel,tools,12);
        refresh.setOnClickListener(v->dispatch(0));export.setOnClickListener(v->dispatch(1));choose.setOnClickListener(v->dispatch(2));clear.setOnClickListener(v->showConfirmation(false));
        restore.setOnClickListener(v->showConfirmation(8));
        LinearLayout content=new LinearLayout(context);content.setGravity(Gravity.TOP);
        LinearLayout roster=card();LinearLayout heading=new LinearLayout(context);heading.addView(text("已登记人员与状态",20,true),new LinearLayout.LayoutParams(0,-2,1));count=text("",14,false);heading.addView(count);roster.addView(heading);
        people=column();add(roster,people,10);LinearLayout pager=new LinearLayout(context);previous=button("上一页");next=button("下一页");pager.addView(previous,new LinearLayout.LayoutParams(0,-2,1));pager.addView(next,new LinearLayout.LayoutParams(0,-2,1));add(roster,pager,10);previous.setOnClickListener(v->page(-1));next.setOnClickListener(v->page(1));
        content.addView(roster,new LinearLayout.LayoutParams(0,-2,3));candidatePanel=card();candidatePanel.addView(text("导入预检（尚未激活）",20,true));candidate=text("选择加密库后，认证预检摘要将显示在这里。",16,false);add(candidatePanel,candidate,12);
        confirm=button("确认导入并替换本场库");discard=button("取消本次预检");add(candidatePanel,confirm,16);add(candidatePanel,discard,8);confirm.setOnClickListener(v->showConfirmation(true));discard.setOnClickListener(v->dispatch(4));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,2);cp.leftMargin=dp(16);content.addView(candidatePanel,cp);add(panel,content,16);
        add(panel,text("密钥不随库文件、人员列表或日志保存。选择文件不是导入完成，导出请求也不等于已生成有效文件。",13,false),14);
        refreshButtons();
    }
    public void render(Model nextModel){main();if(nextModel==null)throw new IllegalArgumentException("Model required");if(dialog!=null&&!sameConfirmation(confirmationModel,nextModel))dismissConfirmation();model=nextModel;ticket.update(model.requestToken,model.revision);
        mode.setText(model.simulation?"TEST合成库管理 · 非真实人员或识别效果":"本场人脸库管理 · 设备与算法仍需确认");
        current.setText("赛事："+model.eventName+"\n批次："+model.batchId+"\n模型："+model.modelLabel+"\n库版本："+model.libraryRevision+"\n库状态："+libraryLabel(model.libraryState)+"\n已登记："+(model.countsKnown?model.storedCount+" 人":"待核")+" · 索引可用："+(model.countsKnown?model.readyCount+" 人":"待核"));
        current.append("\n上一选择批次："+(model.previousBatchId.isEmpty()?"无或待核":model.previousBatchId));restore.setContentDescription("恢复上一库选择，批次 "+(model.previousBatchId.isEmpty()?"无或待核":model.previousBatchId));
        status.setText(stateLabel(model.state)+(model.notice.isEmpty()?"":"\n"+model.notice));status.setTextColor(model.state==State.FAILED?0xffa13730:0xff1769b7);count.setText((model.countsKnown?model.totalPeople+" 人":"人数待核")+" · "+(model.page+1)+" / "+model.pageCount+" 页");
        people.removeAllViews();if(!model.countsKnown||model.rows.isEmpty())people.addView(text(!model.countsKnown?"人数与人员状态待核，不能将占位零值当作空库。":busy(model.state)?"人员状态处理中，请稍候。":"本页没有可展示人员；不从报到日志推断已入库。",15,false));
        if(model.countsKnown)for(PersonRow row:model.rows){LinearLayout entry=column();entry.setPadding(0,dp(10),0,dp(10));entry.addView(text("比赛号码："+row.bib+"    "+row.name,17,true));add(entry,text("组别："+row.group+"\n登记状态："+(row.registrationStatus==RegistrationStatus.ENROLLED?"已登记":"待核对")+(row.notice.isEmpty()?"":"\n"+row.notice),14,false),4);people.addView(entry);}
        ImportPreview p=model.preview;candidate.setText(p==null?"尚无已认证预检候选。选择文件后仍需预检与明确确认。":"来源赛事名称："+p.eventName+"\n来源批次："+p.batchId+"\n来源模型与特征类型："+p.modelLabel+"\n来源版本："+p.sourceRevision+"\n候选人数："+p.personCount+"\n将替换当前库条数："+p.replaceCount+"\n"+p.compatibilityNotice+"\n预检不代表已写入或激活。");refreshButtons();
    }
    private void showConfirmation(boolean importing){showConfirmation(importing?3:5);}
    private void showConfirmation(int kind){boolean importing=kind==3,restoring=kind==8;Model captured=model;if(captured==null||!allowed(kind)||!ticket.available()||dialog!=null||!isAttachedToWindow())return;
        confirmationModel=captured;long generation=++confirmationGeneration;
        dialog=new AlertDialog.Builder(getContext()).setTitle(restoring?"确认恢复上一库选择":importing?"确认替换本场人脸库":"确认清理本场人脸库")
                .setMessage(restoring?"将恢复上一选择批次："+captured.previousBatchId+"\n需先关闭当前索引并重新加载上一库。不合并人员，不改报到或刷脸审计；失败时由系统恢复当前库或明确阻断。确认继续？":importing?"已完成预检，尚未导入。确认后将替换当前本场人脸库，旧识别会话必须失效。请核对赛事、模型和候选人数。":"仅清理当前赛事人脸库，不撤销已保存报到。清理后无法继续使用当前库刷脸核验。确认继续？")
                .setNegativeButton("取消",(d,w)->{})
                .setPositiveButton(restoring?"确认恢复":importing?"确认导入":"确认清理",(d,w)->{if(generation!=confirmationGeneration||!sameConfirmation(captured,model)||!allowed(kind)||!ticket.take())return;refreshButtons();if(restoring)actions.onRestorePreviousLibrary(captured.operationId,captured.requestToken,captured.revision,true);else if(importing)actions.onConfirmImport(captured.operationId,captured.preview.preflightToken,captured.requestToken,captured.revision,true);else actions.onClearLibrary(captured.operationId,captured.requestToken,captured.revision,true);})
                .create();dialog.setOnDismissListener(d->{if(generation==confirmationGeneration){dialog=null;confirmationModel=null;confirmationGeneration++;refreshButtons();}});dialog.show();refreshButtons();
    }
    private static boolean sameConfirmation(Model before,Model after){return before!=null&&after!=null&&before.operationId.equals(after.operationId)&&before.requestToken.equals(after.requestToken)&&before.revision==after.revision&&before.batchId.equals(after.batchId)&&before.modelLabel.equals(after.modelLabel)&&before.libraryRevision.equals(after.libraryRevision)&&before.state==after.state&&before.libraryState==after.libraryState&&before.simulation==after.simulation&&before.countsKnown==after.countsKnown&&before.storedCount==after.storedCount&&before.confirmImportEnabled==after.confirmImportEnabled&&before.clearEnabled==after.clearEnabled&&before.restoreEnabled==after.restoreEnabled&&before.previousBatchId.equals(after.previousBatchId)&&samePreview(before.preview,after.preview)&&!busy(after.state);}
    private static boolean samePreview(ImportPreview a,ImportPreview b){return a==null||b==null?a==b:a.preflightToken.equals(b.preflightToken)&&a.batchId.equals(b.batchId)&&a.modelLabel.equals(b.modelLabel)&&a.sourceRevision.equals(b.sourceRevision)&&a.personCount==b.personCount&&a.replaceCount==b.replaceCount;}
    private void dismissConfirmation(){confirmationGeneration++;AlertDialog old=dialog;dialog=null;confirmationModel=null;if(old!=null)old.dismiss();}
    private void dispatch(int kind){Model m=model;if(m==null||dialog!=null||!allowed(kind)||!ticket.take())return;refreshButtons();switch(kind){case 0:actions.onRefresh(m.operationId,m.requestToken,m.revision);break;case 1:actions.onExport(m.operationId,m.requestToken,m.revision);break;case 2:actions.onChooseImport(m.operationId,m.requestToken,m.revision);break;case 4:actions.onCancelPreflight(m.operationId,m.preview.preflightToken,m.requestToken,m.revision);break;default:dismissConfirmation();actions.onBack(m.operationId,m.requestToken,m.revision);}}
    private void page(int delta){Model m=model;if(m==null||!allowed(6)||dialog!=null)return;int target=m.page+delta;if(target<0||target>=m.pageCount||!ticket.take())return;refreshButtons();actions.onPageRequested(target,m.operationId,m.requestToken,m.revision);}
    private boolean allowed(int kind){if(model==null||model.operationId.isEmpty())return false;switch(kind){case 0:return model.refreshEnabled&&!busy(model.state);case 1:return model.exportEnabled&&!busy(model.state);case 2:return model.chooseImportEnabled&&!busy(model.state);case 3:return model.confirmImportEnabled&&model.preview!=null&&model.state==State.IMPORT_REVIEW;case 4:return model.cancelPreviewEnabled&&model.preview!=null;case 5:return model.clearEnabled&&!busy(model.state);case 6:return model.pageEnabled&&!busy(model.state);case 8:return model.restoreEnabled&&!model.previousBatchId.isEmpty()&&model.state==State.READY&&(model.libraryState==LibraryState.READY||model.libraryState==LibraryState.EMPTY)&&model.preview==null;default:return model.backEnabled;}}
    private void refreshButtons(){Button[] buttons={refresh,export,choose,confirm,discard,clear,previous,back,restore};for(int i=0;i<buttons.length;i++){boolean on=dialog==null&&ticket.available()&&allowed(i)&&(i!=6||model.page>0);enabled(buttons[i],on);}enabled(next,dialog==null&&ticket.available()&&allowed(6)&&model.page+1<model.pageCount);}
    public void unbind(){main();dismissConfirmation();ticket.clear();model=null;mode.setText("库管理会话已解绑");current.setText("");status.setText("");count.setText("");candidate.setText("尚无预检候选");people.removeAllViews();refreshButtons();}
    @Override protected void onDetachedFromWindow(){unbind();super.onDetachedFromWindow();}
    private static boolean busy(State state){return state==State.LOADING||state==State.EXPORTING||state==State.CHOOSING_IMPORT||state==State.PREFLIGHTING||state==State.IMPORTING||state==State.CLEARING;}
    private static String stateLabel(State state){switch(state){case LOADING:return "读取库状态中";case READY:return "等待明确操作";case EXPORTING:return "正在导出，尚未确认有效文件";case CHOOSING_IMPORT:return "请选择库文件，尚未导入";case PREFLIGHTING:return "正在认证预检，不会激活库";case IMPORT_REVIEW:return "预检完成，等待确认导入";case IMPORTING:return "正在激活导入，请勿重复操作";case CLEARING:return "正在清理本场库";default:return "操作未完成，原库状态以当前摘要为准";}}
    private static String libraryLabel(LibraryState state){switch(state){case ABSENT:return "未建立";case EMPTY:return "空库";case LOADING:return "加载中";case READY:return "索引就绪";case DIRTY:return "待重建索引";case INCOMPATIBLE:return "模型不兼容";default:return "库损坏";}}
    private static void enabled(Button b,boolean on){b.setEnabled(on);b.setAlpha(on?1f:.45f);}
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI main thread required");}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout c=new LinearLayout(getContext());c.setOrientation(LinearLayout.VERTICAL);return c;}
    private TextView text(String value,int size,boolean bold){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(0xff162638);t.setSaveEnabled(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable bg=new GradientDrawable();bg.setColor(0xffffffff);bg.setCornerRadius(dp(10));bg.setStroke(dp(1),0xffdce4ec);c.setBackground(bg);return c;}
    private Button button(String title){Button b=new Button(getContext());b.setText(title);b.setAllCaps(false);b.setTextColor(0xff1769b7);b.setMinimumHeight(dp(48));return b;}
    private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(gap);parent.addView(child,p);}
}
