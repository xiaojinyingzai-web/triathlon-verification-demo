package com.triathlon.prep.ui;

import android.content.Context;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Host-controlled intake notice. Never starts, advances or retries a verification by itself. */
public final class AutomaticIntakeControl extends LinearLayout {
    public enum State { DISABLED, WAITING_CARD, BUSY, WAITING_NEXT, SAME_CARD, BLOCKED }
    public interface Actions { void onRecheckRequested(); }
    public static final class Model {
        public final State state;public final boolean simulation,recheckEnabled;public final String notice;
        public Model(State state,boolean simulation,boolean recheckEnabled,String notice){
            if(state==null||recheckEnabled&&state!=State.SAME_CARD)throw new IllegalArgumentException("intake state required");
            this.state=state;this.simulation=simulation;this.recheckEnabled=recheckEnabled;this.notice=notice;
        }
    }
    private final TextView status;private final Button recheck;private Model model;
    public AutomaticIntakeControl(Context c,Actions actions){
        super(c);if(actions==null)throw new IllegalArgumentException("actions required");setOrientation(VERTICAL);
        status=new TextView(c);status.setTextSize(15);status.setTextColor(0xff637487);addView(status);
        recheck=new Button(c);recheck.setAllCaps(false);recheck.setText("重核当前证件");addView(recheck);
        recheck.setOnClickListener(v->{if(model!=null&&model.recheckEnabled&&recheck.isEnabled()){recheck.setEnabled(false);actions.onRecheckRequested();}});
        render(new Model(State.DISABLED,false,false,"自动接卡状态待同步"));
    }
    public void render(Model m){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI update on main thread");if(m==null)throw new IllegalArgumentException("model required");model=m;
        String label;switch(m.state){case WAITING_CARD:label="自动待机 · 请刷身份证";break;case BUSY:label="正在核验 · 请勿更换证件";break;
            case WAITING_NEXT:label="本轮已结束 · 等待下一张不同证件";break;case SAME_CARD:label="当前证件已处理 · 如需重核请由工作人员确认";break;
            case BLOCKED:label="自动接卡暂停 · 请先处理当前问题";break;default:label="自动接卡未启用";break;}
        if(m.simulation)label="虚构刷卡演示 · "+label;if(m.notice!=null&&!m.notice.trim().isEmpty())label+="\n"+m.notice;status.setText(label);
        recheck.setVisibility(m.state==State.SAME_CARD?VISIBLE:GONE);recheck.setEnabled(m.recheckEnabled);recheck.setAlpha(m.recheckEnabled?1f:.45f);
    }
}
