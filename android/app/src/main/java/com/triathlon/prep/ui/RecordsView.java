package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native presentation only. Presenter supplies filtered records and detail selection. */
public final class RecordsView extends ScrollView {
    public enum Filter { ALL, PASSED, REJECTED, UNAVAILABLE }
    public enum Result { PASSED, REJECTED, UNAVAILABLE }
    public interface Actions {
        void onBack();
        void onFilterChanged(Filter filter);
        void onRecordSelected(String sessionId);
        void onCloseDetail();
    }
    /** Display fields from an existing log; no identity, image or verdict generation. */
    public static final class Row {
        public final String sessionId, time, maskedNumber, reason, scoreAndThreshold;
        public final String liveness, source, saveStatus, algorithmVersion, error;
        public final Result result;
        public final boolean simulation;
        public Row(String sessionId, String time, String maskedNumber, Result result,
                String reason, String scoreAndThreshold, String liveness, String source,
                String saveStatus, String algorithmVersion, String error, boolean simulation) {
            if(sessionId==null||sessionId.trim().isEmpty()||result==null)
                throw new IllegalArgumentException("sessionId/result required");
            this.sessionId=sessionId;this.time=time;this.maskedNumber=maskedNumber;
            this.result=result;this.reason=reason;this.scoreAndThreshold=scoreAndThreshold;
            this.liveness=liveness;this.source=source;this.saveStatus=saveStatus;
            this.algorithmVersion=algorithmVersion;this.error=error;this.simulation=simulation;
        }
    }
    public static final class Model {
        public final boolean simulation, loading;
        public final Filter filter;
        public final List<Row> rows;
        public final String selectedSessionId, dataNotice, loadError;
        public Model(boolean simulation, boolean loading, Filter filter, List<Row> rows,
                String selectedSessionId, String dataNotice, String loadError) {
            if(filter==null||rows==null)throw new IllegalArgumentException("filter/rows required");
            List<Row> copy=new ArrayList<>(rows);Set<String> ids=new HashSet<>();
            for(Row r:copy)if(r==null||!ids.add(r.sessionId))throw new IllegalArgumentException("null/duplicate row");
            this.simulation=simulation;this.loading=loading;this.filter=filter;
            this.rows=Collections.unmodifiableList(copy);this.selectedSessionId=selectedSessionId;
            this.dataNotice=dataNotice;this.loadError=loadError;
        }
    }
    private static final int INK=0xff162638,MUTED=0xff637487,BLUE=0xff1769b7,LINE=0xffdce4ec;
    private final LinearLayout records;
    private final TextView mode, count, dataNotice;
    private final Button[] filters=new Button[4];
    private final Actions actions;
    private final Button previousPage,nextPage;
    private java.util.function.IntConsumer onPageRequested;
    private Model currentModel;
    private int page,pageCount;
    private boolean pagePending;

    public RecordsView(Context context,Actions actions) {
        super(context);if(actions==null)throw new IllegalArgumentException("actions required");this.actions=actions;
        setFillViewport(true);setBackgroundColor(0xfff5f7fa);
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));
        addView(panel,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        Button back=button("← 返回核验",BLUE);back.setContentDescription("返回核验主界面");back.setOnClickListener(v->this.actions.onBack());panel.addView(back);
        LinearLayout header=row();LinearLayout heading=column();heading.addView(text("核验记录",25,INK,true));heading.addView(text("按本次会话查看结果与原因",14,MUTED,false));header.addView(heading,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        mode=text("",13,0xff825409,true);mode.setPadding(dp(9),dp(7),dp(9),dp(7));mode.setBackground(background(0xfffff0d6,0xfffff0d6,8));header.addView(mode);add(panel,header,14);
        dataNotice=text("",14,MUTED,false);add(panel,dataNotice,16);
        LinearLayout filterBar=row();String[] labels={"全部","通过","不通过","无法核验"};Filter[] values=Filter.values();
        for(int i=0;i<4;i++){final Filter filter=values[i];filters[i]=button(labels[i],MUTED);filters[i].setTextSize(14);filters[i].setMinWidth(0);filters[i].setPadding(dp(3),0,dp(3),0);filters[i].setOnClickListener(v->{if(currentModel==null||currentModel.loading||pagePending)return;clearDetail();pagePending=true;refreshPages();this.actions.onFilterChanged(filter);});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);if(i>0)p.leftMargin=dp(6);filterBar.addView(filters[i],p);}add(panel,filterBar,14);
        LinearLayout listHeading=row();listHeading.addView(text("最近记录",18,INK,true),new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));count=text("",14,MUTED,false);listHeading.addView(count);add(panel,listHeading,20);
        records=column();add(panel,records,10);
        LinearLayout paging=row();previousPage=button("上一页",BLUE);nextPage=button("下一页",BLUE);
        paging.addView(previousPage,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1);pp.leftMargin=dp(12);paging.addView(nextPage,pp);add(panel,paging,12);
        previousPage.setOnClickListener(v->requestPage(page-1));nextPage.setOnClickListener(v->requestPage(page+1));refreshPages();
        TextView note=text("日志仅保存号码尾号；姓名、证件照与现场照未保存，不提供历史照片查询。",14,0xff4b6278,false);note.setPadding(dp(14),dp(14),dp(14),dp(14));note.setBackground(background(0xffeef5fc,0xffd6e1ec,10));add(panel,note,16);
        TextView foot=text("核验记录展示 · 设备与算法实测待完成\n证件尾号不能作为跨会话唯一身份索引",12,MUTED,false);foot.setGravity(Gravity.CENTER);add(panel,foot,18);
    }

    /** Main-thread rendering only; no disk access, filtering logic or fixture data. */
    public void render(Model m) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("render on main thread");
        if(m==null)throw new IllegalArgumentException("model required");
        currentModel=m;pagePending=false;refreshPages();
        mode.setText(m.simulation?"虚构测试":"正式模式");
        dataNotice.setText(value(m.dataNotice));count.setText(m.loading?"读取中":m.rows.size()+" 条");
        for(int i=0;i<filters.length;i++){boolean selected=Filter.values()[i]==m.filter;filters[i].setSelected(selected);filters[i].setTextColor(selected?BLUE:MUTED);filters[i].setBackground(background(selected?0xffe3eef9:Color.WHITE,selected?BLUE:LINE,9));filters[i].setContentDescription(filters[i].getText()+(selected?"，已选择":""));filters[i].setEnabled(!m.loading);}
        records.removeAllViews();
        if(m.loading){empty("正在读取记录","请稍候。");return;}
        if(m.loadError!=null&&!m.loadError.trim().isEmpty()){count.setText("读取失败");empty("无法读取记录",m.loadError);return;}
        if(m.rows.isEmpty()){empty("暂无核验记录",m.filter==Filter.ALL?"完成核验并成功保存后，才会有可查询记录。":"此筛选条件下没有记录，可切换到全部查看。");return;}
        for(Row r:m.rows){
            boolean open=r.sessionId.equals(m.selectedSessionId);LinearLayout card=column();card.setPadding(dp(14),dp(14),dp(14),dp(14));card.setBackground(background(Color.WHITE,LINE,12));
            LinearLayout summary=column();summary.setMinimumHeight(dp(76));summary.setFocusable(true);summary.setClickable(true);summary.setContentDescription("记录 "+r.sessionId+"，"+(open?"收起详情":"查看详情"));summary.setOnClickListener(v->{if(pagePending||currentModel!=m||currentModel.loading)return;if(open)this.actions.onCloseDetail();else this.actions.onRecordSelected(r.sessionId);});
            LinearLayout top=row();top.addView(text(value(r.time),17,INK,true),new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));TextView badge=text(resultLabel(r),13,resultColor(r.result),true);badge.setPadding(dp(6),dp(4),dp(6),dp(4));badge.setBackground(background(resultBackground(r.result),resultBackground(r.result),6));top.addView(badge);summary.addView(top);
            add(summary,text(r.sessionId,13,MUTED,false),7);add(summary,text("证件尾号 "+value(r.maskedNumber),14,MUTED,false),4);
            if(open||m.selectedSessionId==null){add(summary,text(value(r.reason),15,MUTED,false),7);add(summary,text(open?"收起详情 ↑":"查看详情 ↓",13,BLUE,false),4);}card.addView(summary);
            if(open){View divider=new View(getContext());divider.setBackgroundColor(LINE);add(card,divider,12);divider.getLayoutParams().height=dp(1);add(card,text("本轮记录详情",17,INK,true),12);
                detailPair(card,"比对来源",r.source,"相似度 / 阈值",r.scoreAndThreshold);
                detailPair(card,"活体结果",r.liveness,"算法版本",r.algorithmVersion);
                detailPair(card,"错误码",r.error,"保存状态",r.saveStatus);
                Button close=button("返回列表",BLUE);close.setOnClickListener(v->this.actions.onCloseDetail());add(card,close,14);
            }
            add(records,card,10);
        }
    }
    public void setOnPageRequested(java.util.function.IntConsumer callback){
        main();onPageRequested=callback;refreshPages();
    }
    public void renderPage(int page,int pageCount,int totalMatched){
        main();if(page<0||pageCount<0||totalMatched<0||pageCount==0&&page!=0||pageCount>0&&page>=pageCount)throw new IllegalArgumentException("Invalid record page");
        if(this.page!=page&&currentModel!=null&&currentModel.selectedSessionId!=null)clearDetail();
        this.page=page;this.pageCount=pageCount;pagePending=false;
        if(currentModel!=null&&!currentModel.loading&&(currentModel.loadError==null||currentModel.loadError.trim().isEmpty()))count.setText(totalMatched+" 条 · "+(pageCount==0?0:page+1)+" / "+pageCount+" 页");refreshPages();
    }
    private void requestPage(int target){
        if(onPageRequested==null||currentModel==null||currentModel.loading||pagePending||target<0||target>=pageCount)return;
        clearDetail();pagePending=true;refreshPages();onPageRequested.accept(target);
    }
    private void clearDetail(){if(currentModel!=null)render(new Model(currentModel.simulation,currentModel.loading,currentModel.filter,currentModel.rows,null,currentModel.dataNotice,currentModel.loadError));}
    private void refreshPages(){boolean enabled=onPageRequested!=null&&currentModel!=null&&!currentModel.loading&&!pagePending&&(currentModel.loadError==null||currentModel.loadError.trim().isEmpty());previousPage.setEnabled(enabled&&page>0);nextPage.setEnabled(enabled&&page+1<pageCount);for(Button filter:filters)if(filter!=null)filter.setEnabled(currentModel!=null&&!currentModel.loading&&!pagePending);previousPage.setVisibility(onPageRequested==null?View.GONE:View.VISIBLE);nextPage.setVisibility(onPageRequested==null?View.GONE:View.VISIBLE);}
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI main thread required");}
    private void empty(String heading,String description){LinearLayout card=column();card.setPadding(dp(24),dp(45),dp(24),dp(45));card.setGravity(Gravity.CENTER);card.setBackground(background(Color.WHITE,LINE,12));TextView h=text(heading,21,INK,true);h.setGravity(Gravity.CENTER);card.addView(h);TextView d=text(description,16,MUTED,false);d.setGravity(Gravity.CENTER);add(card,d,14);Button back=button("返回核验主界面",BLUE);back.setOnClickListener(v->actions.onBack());add(card,back,22);records.addView(card);}
    private void detailPair(LinearLayout parent,String label1,String value1,String label2,String value2){LinearLayout r=row();r.setGravity(Gravity.TOP);r.addView(detail(label1,value1),new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1);p.leftMargin=dp(16);r.addView(detail(label2,value2),p);add(parent,r,12);}
    private LinearLayout detail(String label,String value){LinearLayout l=column();l.addView(text(label,12,MUTED,false));add(l,text(value(value),15,INK,false),4);return l;}
    private String resultLabel(Row r){String label=r.result==Result.PASSED?"通过":r.result==Result.REJECTED?"不通过":"无法核验";return r.simulation?"模拟"+label:label;}
    private int resultColor(Result r){return r==Result.PASSED?0xff236c47:r==Result.REJECTED?0xffa13730:0xff8b5b0f;}
    private int resultBackground(Result r){return r==Result.PASSED?0xffedf7f1:r==Result.REJECTED?0xfffcf0ef:0xfffff7e9;}
    private String value(String s){return s==null||s.trim().isEmpty()?"—":s;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);p.topMargin=dp(gap);parent.addView(child,p);}
    private GradientDrawable background(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private Button button(String label,int color){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(color);b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(5),dp(12),dp(5));b.setBackground(background(Color.WHITE,LINE,9));return b;}
}
