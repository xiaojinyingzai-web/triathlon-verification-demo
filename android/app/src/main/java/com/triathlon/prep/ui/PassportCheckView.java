package com.triathlon.prep.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.triathlon.prep.core.DocumentNumber;
import com.triathlon.prep.core.ManualPassportCheck;
import com.triathlon.prep.core.ReceiptPrintController;
import com.triathlon.prep.core.Verification;

/** Staff-only passive search/selection. Host owns identity, manual commit, print permissions and IO. */
public final class PassportCheckView extends ManualSearchLayout {
    public interface Actions {
        void onQueryChanged(String query);void onPageRequested(String queryToken,int page);
        void onCandidateSelected(String token);void onConfirmAndPrint(String selectionToken);
        default void onReprint(String selectionToken){}
        default void onReprint(String selectionToken,String requestToken,long requestRevision,boolean confirmed){}
        void onBack();
    }
    public static final class Model {
        public final ManualPassportCheck.Snapshot snapshot;
        public final boolean confirmEnabled,reprintEnabled,backEnabled;
        public final ReceiptPrintController.Status printStatus;
        public final ReceiptPrintController.Failure printFailure;
        public final String notice;public final ReceiptPrintController.Raster raster;
        public final String requestToken;public final long requestRevision;
        public Model(ManualPassportCheck.Snapshot snapshot,boolean confirmEnabled,boolean reprintEnabled,boolean backEnabled,
                ReceiptPrintController.Status printStatus,ReceiptPrintController.Failure printFailure,String notice,ReceiptPrintController.Raster raster){
            this(snapshot,confirmEnabled,reprintEnabled,backEnabled,printStatus,printFailure,notice,raster,"",0);
        }
        public Model(ManualPassportCheck.Snapshot snapshot,boolean confirmEnabled,boolean reprintEnabled,boolean backEnabled,
                ReceiptPrintController.Status printStatus,ReceiptPrintController.Failure printFailure,String notice,ReceiptPrintController.Raster raster,String requestToken,long requestRevision){
            if(requestToken==null||requestRevision<0)throw new IllegalArgumentException("request token/revision required");
            if(snapshot==null||printStatus==null||printFailure==null||notice==null)throw new IllegalArgumentException("manual model required");
            boolean blocked=busy(snapshot.state)||printStatus==ReceiptPrintController.Status.SENDING;
            if(confirmEnabled&&(snapshot.selected==null||snapshot.selected.completed||blocked))throw new IllegalArgumentException("invalid manual confirmation permission");
            if(reprintEnabled&&(snapshot.selected==null||!snapshot.selected.completed||blocked))throw new IllegalArgumentException("committed selection required for reprint");
            this.snapshot=snapshot;this.confirmEnabled=confirmEnabled;this.reprintEnabled=reprintEnabled;this.backEnabled=backEnabled;
            this.printStatus=printStatus;this.printFailure=printFailure;this.notice=notice;this.raster=raster;
            this.requestToken=requestToken;this.requestRevision=requestRevision;
        }
    }
    private final Actions actions;private final Button previous,next;private final TextView pageText;
    private final ImageView image;private Model model;private boolean binding,pending,needsFocus=true,queryDirty;
    private ManualPassportCheck.Snapshot pendingSnapshot;
    private ReceiptPrintController.Raster displayedRaster;
    private boolean awaitingQuery;
    private String awaitingQueryToken;
    private Boolean simulationMode;
    private final ReprintConfirmation confirmation=new ReprintConfirmation();
    private final Runnable changed=()->submitQuery();
    public PassportCheckView(Context context,Actions actions){
        super(context);if(actions==null)throw new IllegalArgumentException("manual actions required");this.actions=actions;
        LinearLayout paging=new LinearLayout(context);paging.setOrientation(LinearLayout.HORIZONTAL);paging.setGravity(Gravity.CENTER_VERTICAL);
        previous=button("上一页");next=button("下一页");pageText=text("",14,false);pageText.setGravity(Gravity.CENTER);paging.addView(previous);paging.addView(pageText,new LinearLayout.LayoutParams(0,-2,1));paging.addView(next);add(results,paging,8);
        image=new ImageView(context);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("人工核验小票同源预览，TEST标记在纸外");add(detailPanel,image,12);image.setVisibility(GONE);
        search.setOnClickListener(v->submitQuery());query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(Editable value){if(binding||model==null)return;awaitingQuery=true;awaitingQueryToken=model.snapshot.queryToken;queryDirty=true;clearSelection();query.removeCallbacks(changed);query.postDelayed(changed,180);}
        });
        confirm.setOnClickListener(v->{Model m=model;if(m==null||!m.confirmEnabled||blocked()||m.snapshot.selected==null)return;begin();actions.onConfirmAndPrint(m.snapshot.selected.token);});
        reprint.setText("补打小票");reprint.setOnClickListener(v->requestReprint());
        previous.setOnClickListener(v->page(-1));next.setOnClickListener(v->page(1));back.setOnClickListener(v->{if(model!=null&&model.backEnabled&&!pending){confirmation.dismiss();query.removeCallbacks(changed);hideKeyboard();actions.onBack();}});
        previous.setEnabled(false);next.setEnabled(false);
        confirmation.setOnClosed(()->{if(model!=null)reprint.setEnabled(model.reprintEnabled&&!blocked()&&confirmation.available());});
    }
    public void render(Model m){main();if(m==null)throw new IllegalArgumentException("manual model required");Model old=model;model=m;
        if(pending&&m.snapshot!=pendingSnapshot)pending=false;
        if(old==null){binding=true;try{query.setText(m.snapshot.query);}finally{binding=false;}}
        queryDirty=!query.getText().toString().trim().equals(m.snapshot.query)||(awaitingQuery&&m.snapshot.queryToken.equals(awaitingQueryToken));
        if(!queryDirty){awaitingQuery=false;awaitingQueryToken=null;}
        boolean blocked=blocked();query.setEnabled(m.snapshot.state!=ManualPassportCheck.State.NO_ROSTER&&m.snapshot.state!=ManualPassportCheck.State.SAVING&&m.snapshot.state!=ManualPassportCheck.State.PRINTING&&m.printStatus!=ReceiptPrintController.Status.SENDING);
        confirmation.update(actionIdentity(m),m.requestToken,m.requestRevision,m.reprintEnabled&&!blocked&&m.snapshot.selected!=null&&m.snapshot.selected.completed);
        updateMode(m);
        summary.setText(m.snapshot.state==ManualPassportCheck.State.NO_ROSTER?"没有有效名单，不能人工核验或打印。":"仅搜索本机当前有效名单；多条结果须明确选择。");
        status.setText(m.snapshot.notice+(m.notice.isEmpty()||m.notice.equals(m.snapshot.notice)?"":"\n"+m.notice));
        if(m.printStatus==ReceiptPrintController.Status.FAILED||m.printStatus==ReceiptPrintController.Status.CANCELLED)status.append("\n打印未完成，请先核对出纸情况，再明确补打。" );
        status.setTextColor(m.snapshot.state==ManualPassportCheck.State.FAILED||m.printStatus==ReceiptPrintController.Status.FAILED?0xffa13730:0xff637487);
        results.removeAllViews();
        if(!queryDirty){for(ManualPassportCheck.Candidate candidate:m.snapshot.matches){Button row=button(candidate.name+"   比赛号码："+candidate.bib+"\n组别："+candidate.group+"   团体："+(candidate.team.isEmpty()?"未提供":candidate.team));row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);row.setEnabled(!blocked);
            row.setOnClickListener(v->{if(model==null||blocked()||!model.snapshot.queryToken.equals(m.snapshot.queryToken))return;clearSelection();begin();hideKeyboard();actions.onCandidateSelected(candidate.token);});add(results,row,6);
        }}
        resultSummary.setText(queryDirty?"查询已改变，旧结果/选择不可使用。":m.snapshot.query.isEmpty()?"请输入查询条件，不默认选择。":m.snapshot.totalMatches==0?"没有匹配人员。":"共 "+m.snapshot.totalMatches+" 人，请明确选择。");
        LinearLayout paging=new LinearLayout(getContext());paging.setOrientation(LinearLayout.HORIZONTAL);paging.setGravity(Gravity.CENTER_VERTICAL);detach(previous);detach(next);detach(pageText);paging.addView(previous);paging.addView(pageText,new LinearLayout.LayoutParams(0,-2,1));paging.addView(next);add(results,paging,8);
        pageText.setText((m.snapshot.pageCount==0?0:m.snapshot.page+1)+" / "+m.snapshot.pageCount);previous.setEnabled(!blocked&&m.snapshot.page>0);next.setEnabled(!blocked&&m.snapshot.page+1<m.snapshot.pageCount);
        if(queryDirty||pending||m.snapshot.selected==null)clearSelection();else{ManualPassportCheck.Selection selection=m.snapshot.selected;
            detailText.setText("姓名："+selection.name+"\n比赛号码："+selection.bib+"\n组别："+selection.group+"\n团体："+(selection.team.isEmpty()?"未提供":selection.team)+"\n登记类型："+(selection.documentType==DocumentNumber.Type.PASSPORT?"护照":"身份证")+"\n登记证件号码："+selection.documentNumber+"\n"+(selection.completed?"已办理 · 人工核验已保存":"请核对护照原件与本人，再明确确认人工核验。"));
            confirm.setVisibility(selection.completed?GONE:VISIBLE);reprint.setVisibility(selection.completed?VISIBLE:GONE);confirm.setEnabled(m.confirmEnabled&&!blocked);reprint.setEnabled(m.reprintEnabled&&!blocked&&confirmation.available());
        }
        search.setEnabled(!pending&&!busy(m.snapshot.state)&&m.printStatus!=ReceiptPrintController.Status.SENDING&&m.snapshot.state!=ManualPassportCheck.State.NO_ROSTER);back.setEnabled(m.backEnabled&&!pending);
        if(m.raster!=null&&!queryDirty&&m.snapshot.selected!=null&&m.snapshot.selected.completed){try{if(displayedRaster!=m.raster){image.setImageBitmap(ReceiptBitmapRenderer.paperBitmap(m.raster));displayedRaster=m.raster;}image.setVisibility(VISIBLE);}catch(RuntimeException invalid){image.setImageDrawable(null);displayedRaster=null;image.setVisibility(GONE);status.append("\n小票图像不可用。");}}
        else{image.setImageDrawable(null);image.setVisibility(GONE);displayedRaster=null;}
        if(needsFocus&&query.isEnabled()&&isAttachedToWindow()){needsFocus=false;focusSearch();}
    }
    private void submitQuery(){Model m=model;if(m==null||busy(m.snapshot.state)&&m.snapshot.state!=ManualPassportCheck.State.SEARCHING||m.printStatus==ReceiptPrintController.Status.SENDING||m.snapshot.state==ManualPassportCheck.State.NO_ROSTER)return;
        query.removeCallbacks(changed);awaitingQuery=true;awaitingQueryToken=m.snapshot.queryToken;queryDirty=true;clearSelection();pending=true;pendingSnapshot=m.snapshot;search.setEnabled(false);actions.onQueryChanged(query.getText().toString());}
    private void page(int delta){Model m=model;if(m==null||blocked())return;int page=m.snapshot.page+delta;if(page<0||page>=m.snapshot.pageCount)return;awaitingQuery=true;awaitingQueryToken=m.snapshot.queryToken;queryDirty=true;clearSelection();begin();actions.onPageRequested(m.snapshot.queryToken,page);}
    private void begin(){pending=true;pendingSnapshot=model.snapshot;confirm.setEnabled(false);reprint.setEnabled(false);search.setEnabled(false);previous.setEnabled(false);next.setEnabled(false);back.setEnabled(false);}
    private boolean blocked(){return model==null||pending||queryDirty||busy(model.snapshot.state)||model.printStatus==ReceiptPrintController.Status.SENDING;}
    private void clearSelection(){confirmation.dismiss();detailText.setText("尚未选中有效名单人员");confirm.setEnabled(false);reprint.setEnabled(false);confirm.setVisibility(VISIBLE);reprint.setVisibility(GONE);image.setImageDrawable(null);image.setVisibility(GONE);displayedRaster=null;}
    private static String actionIdentity(Model m){return m.snapshot.selected==null?"":m.snapshot.selected.token+"/"+m.snapshot.queryToken+"/"+m.printStatus+"/"+m.reprintEnabled;}
    private void requestReprint(){Model captured=model;if(captured==null||!captured.reprintEnabled||blocked()||captured.snapshot.selected==null||!isAttachedToWindow())return;
        confirmation.request(getContext(),true,()->model!=null&&!blocked()&&model.reprintEnabled&&actionIdentity(captured).equals(actionIdentity(model))&&captured.requestToken.equals(model.requestToken)&&captured.requestRevision==model.requestRevision,confirmed->{
            begin();actions.onReprint(captured.snapshot.selected.token,captured.requestToken,captured.requestRevision,confirmed);
        });
    }
    private static boolean busy(ManualPassportCheck.State state){return state==ManualPassportCheck.State.SEARCHING||state==ManualPassportCheck.State.SAVING||state==ManualPassportCheck.State.PRINTING;}
    private static void detach(View view){if(view.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)view.getParent()).removeView(view);}
    private void hideKeyboard(){InputMethodManager input=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);if(input!=null)input.hideSoftInputFromWindow(query.getWindowToken(),0);}
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("manual view on main thread");}
    /** Host supplies actual application mode before any manual record exists; never infer it from a name or ID. */
    public void setSimulation(boolean simulation){main();simulationMode=simulation;if(model!=null)updateMode(model);else mode.setText(simulation?"TEST虚构测试 · 工作人员人工核验":"工作人员人工核验");}
    private void updateMode(Model m){boolean test=Boolean.TRUE.equals(simulationMode)||m.snapshot.record!=null&&m.snapshot.record.mode==Verification.Mode.TEST||m.printStatus==ReceiptPrintController.Status.SIMULATED;mode.setText(test?"TEST虚构测试 · MANUAL人工核验，不是自动人脸通过":"MANUAL工作人员人工核验 · 不执行自动人脸比对");}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();needsFocus=true;post(()->{if(model!=null&&query.isEnabled()&&needsFocus){needsFocus=false;focusSearch();}});}
    @Override protected void onDetachedFromWindow(){query.removeCallbacks(changed);binding=true;try{query.setText("");}finally{binding=false;}model=null;pending=false;queryDirty=false;awaitingQuery=false;awaitingQueryToken=null;clearSelection();results.removeAllViews();super.onDetachedFromWindow();}
}
