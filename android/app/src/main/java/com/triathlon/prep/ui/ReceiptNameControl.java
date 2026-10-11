package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Controlled editor: saving and document snapshots belong to Host. */
public final class ReceiptNameControl extends LinearLayout {
    public enum Status { LOADING, READY, SAVING, FAILED }
    public interface Actions { void onSaveRequested(String draft); }
    public static final class Model {
        public final String confirmedName,notice;
        public final Status status;
        public final int maxCodePoints;
        public final boolean enabled;
        public Model(String confirmedName,Status status,int maxCodePoints,boolean enabled,String notice){
            if(status==null||maxCodePoints<1||maxCodePoints>200)throw new IllegalArgumentException("status/length required");
            String name=confirmedName==null?"":confirmedName;
            if(name.codePointCount(0,name.length())>maxCodePoints)throw new IllegalArgumentException("confirmed name too long");
            if(enabled&&(status==Status.LOADING||status==Status.SAVING))throw new IllegalArgumentException("pending editor disabled");
            this.confirmedName=name;this.status=status;this.maxCodePoints=maxCodePoints;this.enabled=enabled;this.notice=notice;
        }
    }
    private final EditText input;
    private final TextView state;
    private final Button save;
    private Model model;
    private boolean requestPending;
    public ReceiptNameControl(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("actions required");setOrientation(VERTICAL);
        TextView title=text("小票顶部赛事名称",20,true);addView(title);
        add(text("留空沿用名单赛事名称；修改不影响已提交的打印任务。",14,false),8);
        input=new EditText(context);input.setTextSize(16);input.setSingleLine(true);input.setHint("留空沿用名单赛事名称");input.setContentDescription("小票顶部赛事名称");add(input,12);
        save=new Button(context);save.setText("保存小票名称");save.setAllCaps(false);add(save,10);state=text("名称设置尚未读取",14,false);add(state,8);
        input.setEnabled(false);save.setEnabled(false);
        save.setOnClickListener(v->{Model m=model;if(m==null||!m.enabled||requestPending)return;
            String raw=input.getText().toString();
            for(int i=0;i<raw.length();){int cp=raw.codePointAt(i);if(Character.isISOControl(cp)){input.setError("名称不能包含换行或控制字符");return;}i+=Character.charCount(cp);}
            String draft=raw.trim();
            if(draft.codePointCount(0,draft.length())>m.maxCodePoints){input.setError("最多"+m.maxCodePoints+"个字符");return;}
            requestPending=true;input.setEnabled(false);save.setEnabled(false);state.setText("正在提交名称设置");actions.onSaveRequested(draft);
        });
    }
    /** Unchanged confirmed values must not wipe an in-progress draft on device ticker renders. */
    public void render(Model m){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI update on main thread");
        if(m==null)throw new IllegalArgumentException("model required");Model previous=model;model=m;
        if(previous==null||!previous.confirmedName.equals(m.confirmedName))input.setText(m.confirmedName);
        requestPending=m.status==Status.LOADING||m.status==Status.SAVING;
        input.setEnabled(m.enabled&&!requestPending);save.setEnabled(m.enabled&&!requestPending);save.setAlpha(save.isEnabled()?1f:.45f);
        String confirmed=m.confirmedName.isEmpty()?"沿用名单赛事名称":m.confirmedName;
        String label;switch(m.status){case LOADING:label="名称设置尚未读取";break;case SAVING:label="正在保存；当前仍为："+confirmed;break;
            case FAILED:label="名称未保存；当前仍为："+confirmed;break;default:label="当前已确认："+confirmed;break;}
        if(m.notice!=null&&!m.notice.trim().isEmpty())label+="\n"+m.notice;state.setText(label);state.setTextColor(m.status==Status.FAILED?0xffa13730:0xff637487);
    }
    private TextView text(String s,int size,boolean bold){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(0xff162638);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void add(android.view.View v,int gap){LayoutParams lp=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);lp.topMargin=Math.round(gap*getResources().getDisplayMetrics().density);addView(v,lp);}
}
