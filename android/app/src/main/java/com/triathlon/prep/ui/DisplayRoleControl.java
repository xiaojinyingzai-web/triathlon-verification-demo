package com.triathlon.prep.ui;

import android.content.Context;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Explicit role selection. Display enumeration and window creation belong to Host. */
public final class DisplayRoleControl extends LinearLayout {
    public enum Status { READY, APPLYING, FAILED }
    public interface Actions {
        void onAssignmentRequested(Integer staffDisplayId,Integer athleteDisplayId);
        default void onClearRequested(){onAssignmentRequested(null,null);}
    }
    public static final class Option {
        public final int displayId;public final String label;
        public Option(int displayId,String label){if(displayId<0||label==null||label.trim().isEmpty())throw new IllegalArgumentException("display option required");this.displayId=displayId;this.label=label;}
    }
    public static final class Model {
        public final List<Option> options;
        public final Integer staffDisplayId,athleteDisplayId;
        public final Status status;public final boolean enabled;public final String notice;
        public Model(List<Option> options,Integer staffDisplayId,Integer athleteDisplayId,Status status,boolean enabled,String notice){
            if(options==null||status==null||status==Status.APPLYING&&enabled)throw new IllegalArgumentException("options/status required");
            HashSet<Integer> ids=new HashSet<>();for(Option o:options){if(o==null||!ids.add(o.displayId))throw new IllegalArgumentException("duplicate/null display");}
            if(staffDisplayId!=null&&!ids.contains(staffDisplayId)||athleteDisplayId!=null&&!ids.contains(athleteDisplayId))throw new IllegalArgumentException("assigned display absent");
            if(athleteDisplayId!=null&&(staffDisplayId==null||athleteDisplayId.equals(staffDisplayId)))throw new IllegalArgumentException("distinct staff/athlete displays required");
            this.options=Collections.unmodifiableList(new ArrayList<>(options));this.staffDisplayId=staffDisplayId;this.athleteDisplayId=athleteDisplayId;this.status=status;this.enabled=enabled;this.notice=notice;
        }
    }
    private final Spinner staff,athlete;
    private final Button apply,clear;private final TextView state;
    private Model model;private boolean pending;
    public DisplayRoleControl(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("actions required");setOrientation(VERTICAL);
        add(text("屏幕用途分配",20),0);add(text("请明确选择工作人员与选手屏幕；显示器名称和尺寸不会自动决定用途。",14),8);
        add(text("工作人员屏幕",16),12);staff=new Spinner(context);add(staff,6);
        add(text("选手屏幕",16),12);athlete=new Spinner(context);add(athlete,6);
        apply=new Button(context);apply.setText("应用屏幕分配");apply.setAllCaps(false);add(apply,12);
        clear=new Button(context);clear.setText("解除屏幕分配");clear.setAllCaps(false);add(clear,8);clear.setEnabled(false);
        state=text("正在读取显示器列表",14);add(state,8);staff.setEnabled(false);athlete.setEnabled(false);apply.setEnabled(false);
        apply.setOnClickListener(v->{if(model==null||!model.enabled||pending)return;
            Integer s=chosen(staff),a=chosen(athlete);
            if(s==null||a==null||a.equals(s)){state.setText("请为工作人员和选手选择两个不同的可用屏幕");return;}
            pending=true;setEnabledControls(false);state.setText("正在应用屏幕分配");actions.onAssignmentRequested(s,a);
        });
        clear.setOnClickListener(v->{if(model==null||!model.enabled||pending)return;pending=true;setEnabledControls(false);state.setText("正在解除屏幕分配");actions.onClearRequested();});
    }
    public void render(Model m){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI update on main thread");if(m==null)throw new IllegalArgumentException("model required");
        Model old=model;model=m;
        if(old==null||!sameOptions(old.options,m.options)||!same(old.staffDisplayId,m.staffDisplayId)||!same(old.athleteDisplayId,m.athleteDisplayId)){
            List<String> labels=new ArrayList<>();labels.add("未分配");for(Option o:m.options)labels.add(o.label+"（ID "+o.displayId+"）");
            ArrayAdapter<String> adapter=new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            staff.setAdapter(adapter);athlete.setAdapter(adapter);staff.setSelection(index(m.staffDisplayId));athlete.setSelection(index(m.athleteDisplayId));
        }
        pending=m.status==Status.APPLYING;setEnabledControls(m.enabled&&!pending);
        String confirmed="工作人员 "+name(m.staffDisplayId)+"；选手 "+name(m.athleteDisplayId);
        String label=m.status==Status.APPLYING?"正在应用；当前仍为："+confirmed:m.status==Status.FAILED?"分配未更新；当前仍为："+confirmed:"已确认："+confirmed;
        if(m.options.size()<2)label=(m.options.isEmpty()?"没有可用显示器，双屏未分配":"仅一个可用显示，双屏未分配")+"\n"+label;
        if(m.notice!=null&&!m.notice.trim().isEmpty())label+="\n"+m.notice;state.setText(label);state.setTextColor(m.status==Status.FAILED?0xffa13730:0xff637487);
    }
    private Integer chosen(Spinner v){int n=v.getSelectedItemPosition();return n<=0||n>model.options.size()?null:model.options.get(n-1).displayId;}
    private int index(Integer id){if(id!=null)for(int i=0;i<model.options.size();i++)if(id==model.options.get(i).displayId)return i+1;return 0;}
    private String name(Integer id){return id==null?"未分配":model.options.get(index(id)-1).label;}
    private void setEnabledControls(boolean enabled){staff.setEnabled(enabled);athlete.setEnabled(enabled);boolean assign=enabled&&model!=null&&model.options.size()>=2;apply.setEnabled(assign);apply.setAlpha(assign?1f:.45f);boolean unassign=enabled&&model!=null&&(model.staffDisplayId!=null||model.athleteDisplayId!=null);clear.setEnabled(unassign);clear.setAlpha(unassign?1f:.45f);}
    private static boolean same(Integer a,Integer b){return a==null?b==null:a.equals(b);}
    private static boolean sameOptions(List<Option> a,List<Option> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i).displayId!=b.get(i).displayId||!a.get(i).label.equals(b.get(i).label))return false;return true;}
    private TextView text(String s,int size){TextView v=new TextView(getContext());v.setText(s);v.setTextSize(size);v.setTextColor(0xff162638);return v;}
    private void add(android.view.View v,int gap){LayoutParams p=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);p.topMargin=Math.round(gap*getResources().getDisplayMetrics().density);addView(v,p);}
}
