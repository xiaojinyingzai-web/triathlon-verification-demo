package com.triathlon.prep.ui;
import android.content.Context;
import android.os.Looper;
import android.view.Gravity;
import android.widget.*;

/** Always-visible staff selection. Never infers a mode from cards, faces or clock time. */
public final class VerificationModeControl extends LinearLayout {
    public enum OperationMode { CHECKIN, FACE }
    public enum SwitchState { STABLE, SWITCHING, FAILED }
    public static final class Model {
        public final OperationMode currentMode,targetMode;public final SwitchState state;
        public final String notice,requestToken;public final long revision;public final boolean enabled;
        public Model(OperationMode currentMode,OperationMode targetMode,SwitchState state,String notice,String requestToken,long revision,boolean enabled){
            if(currentMode==null||targetMode==null||state==null||notice==null||requestToken==null||revision<0||state==SwitchState.SWITCHING&&enabled)throw new IllegalArgumentException("Invalid mode presentation");
            this.currentMode=currentMode;this.targetMode=targetMode;this.state=state;this.notice=notice;this.requestToken=requestToken;this.revision=revision;this.enabled=enabled;
        }
    }
    public interface Actions {void onModeRequested(OperationMode target,String requestToken,long revision);}
    private final Actions actions;private final TextView status;private final Button checkin,face;private Model model;
    private final UiActionTicket ticket=new UiActionTicket();
    public VerificationModeControl(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("Actions required");this.actions=actions;setOrientation(VERTICAL);setPadding(12,12,12,12);setSaveEnabled(false);
        status=new TextView(context);status.setTextSize(17);status.setTextColor(0xff162638);status.setText("当前模式：报到核验（厂家） · 等待状态同步");addView(status);
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);checkin=new Button(context);face=new Button(context);checkin.setText("报到核验（厂家）");face.setText("刷脸核验（百度）");checkin.setAllCaps(false);face.setAllCaps(false);row.addView(checkin,new LinearLayout.LayoutParams(0,-2,1));row.addView(face,new LinearLayout.LayoutParams(0,-2,1));addView(row);
        checkin.setOnClickListener(v->request(OperationMode.CHECKIN));face.setOnClickListener(v->request(OperationMode.FACE));refresh();
    }
    public void render(Model model){main();if(model==null)throw new IllegalArgumentException("Model required");this.model=model;ticket.update(model.requestToken,model.revision);
        String text="当前模式："+label(model.currentMode);if(model.state==SwitchState.SWITCHING)text+="\n切换中 → "+label(model.targetMode)+"，请等待当前事务与相机释放。";else if(model.state==SwitchState.FAILED)text+="\n切换未完成；目标模式尚不可用。";
        status.setText(text+(model.notice.isEmpty()?"":"\n"+model.notice));checkin.setSelected(model.currentMode==OperationMode.CHECKIN);face.setSelected(model.currentMode==OperationMode.FACE);refresh();
    }
    private void request(OperationMode target){Model m=model;if(m==null||!m.enabled||m.state==SwitchState.SWITCHING||m.state==SwitchState.STABLE&&target==m.currentMode||!ticket.take())return;refresh();actions.onModeRequested(target,m.requestToken,m.revision);}
    private void refresh(){boolean ready=model!=null&&model.enabled&&model.state!=SwitchState.SWITCHING&&ticket.available();checkin.setEnabled(ready&&(model.state==SwitchState.FAILED||model.currentMode!=OperationMode.CHECKIN));face.setEnabled(ready&&(model.state==SwitchState.FAILED||model.currentMode!=OperationMode.FACE));for(Button b:new Button[]{checkin,face}){b.setAlpha(b.isEnabled()?1f:.45f);b.setTextColor(b.isSelected()?0xff1769b7:0xff637487);}}
    private static String label(OperationMode mode){return mode==OperationMode.CHECKIN?"报到核验（厂家）":"刷脸核验（百度）";}
    @Override protected void onDetachedFromWindow(){ticket.clear();model=null;refresh();super.onDetachedFromWindow();}
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI main thread required");}
}
