package com.triathlon.prep;

import com.triathlon.prep.core.*;
import com.triathlon.prep.ui.ReceiptPreviewView;

/** Current-session view mapping only; it never resurrects receipts from stored history. */
final class ReceiptPresenter {
 private ReceiptPresenter(){}
 static CheckInReceipt.Document current(Verification flow,String id){
  return flow.snapshot().mode==Verification.Mode.TEST?CheckInReceipt.preparePreview(flow,id):CheckInReceipt.prepare(flow,id);
 }
 static CheckInReceipt.Document current(Verification flow,String id,String heading){return CheckInReceipt.withEventHeading(current(flow,id),heading);}
 static ReceiptPreviewView.Model model(Verification flow,ReceiptPrintController printing){
  return model(flow,printing,flow.snapshot().mode==Verification.Mode.TEST?new CheckInReceipt.Layout(384,12,24):null);
 }
 static ReceiptPreviewView.Model model(Verification flow,ReceiptPrintController printing,CheckInReceipt.Layout layout){
  return model(flow,printing,layout,"");
 }
 static ReceiptPreviewView.Model model(Verification flow,ReceiptPrintController printing,CheckInReceipt.Layout layout,String heading){
  return model(flow,printing,layout,heading,true);
 }
 static ReceiptPreviewView.Model model(Verification flow,ReceiptPrintController printing,CheckInReceipt.Layout layout,String heading,boolean printingAllowed){
  ReceiptPrintController.Snapshot job=printing.snapshot();
  synchronized(flow){
   Verification.Snapshot state=flow.snapshot();String id=state.sessionId.isEmpty()?"NO_CURRENT_SESSION":state.sessionId;
   CheckInReceipt.Document document=current(flow,state.sessionId,heading);
   boolean same=job.sessionId.equals(state.sessionId);
   if(same&&job.status==ReceiptPrintController.Status.SENDING)document=job.document;
   ReceiptPrintController.Status status=same?job.status:ReceiptPrintController.Status.IDLE;
   boolean simulation=state.mode==Verification.Mode.TEST;
   // TEST layout is a synthetic rendering fixture. Delivered printer dots/charset remain unknown.
   boolean eligible=document!=null&&layout!=null&&printingAllowed&&state.checkIn!=CheckIn.State.UNLINKABLE;
   return new ReceiptPreviewView.Model(id,simulation,document,status,same?job.failure:ReceiptPrintController.Failure.NONE,
    same?job.jobId:"",state.checkIn==CheckIn.State.DUPLICATE?"该选手已报到，请勿重复办理。本次未自动打印。":status==ReceiptPrintController.Status.FAILED?"报到已保存；打印未完成，补打前请核对现场出纸。":state.checkIn==CheckIn.State.UNLINKABLE?"同一人员历史报到记录待核对，未开放打印。":!printingAllowed?"打印已关闭或设置未确认，仍可预览。":layout!=null?"":"打印参数未确认，当前仅可预览。",
    eligible&&(status==ReceiptPrintController.Status.IDLE||status==ReceiptPrintController.Status.SIMULATED||status==ReceiptPrintController.Status.ACCEPTED),
    eligible&&(status==ReceiptPrintController.Status.FAILED||status==ReceiptPrintController.Status.CANCELLED),
    document!=null&&status==ReceiptPrintController.Status.SENDING,true);
  }
 }
 /** Runs on Host worker. Screen and sender consume the same canonical PNG/paper bounds. */
 static ReceiptPreviewView.Model graphicModel(Verification flow,ReceiptPrintController printing,CheckInReceipt.Layout layout,ReceiptTemplate template,ReceiptPrintController.GraphicRenderer renderer,boolean permission){
  ReceiptPreviewView.Model base=model(flow,printing,layout,"",permission);ReceiptPrintController.Snapshot job=printing.snapshot();
  boolean inFlight=job.sessionId.equals(base.sessionId)&&job.status==ReceiptPrintController.Status.SENDING;
  CheckInReceipt.Document document=inFlight?job.document:CheckInReceipt.withTemplate(base.document,template);ReceiptPrintController.Raster raster=null;String notice=base.notice;
  if(document!=null)try{
   CheckInReceipt.Layout requested=layout==null?new CheckInReceipt.Layout(384,12,24):layout;
   if(job.raster!=null&&job.sessionId.equals(document.sessionId)&&job.document!=null&&job.document.template.equals(document.template)&&job.document.eventHeading.equals(document.eventHeading)&&job.raster.width==requested.printableDots)raster=job.raster;
   else raster=renderer.render(document,requested);
  }catch(RuntimeException|LinkageError|OutOfMemoryError ex){notice="图形小票预览未完成，请核对模板与打印点宽；未提交打印。";}
  return new ReceiptPreviewView.Model(base.sessionId,base.simulation,document,base.status,base.failure,base.jobId,notice,base.printEnabled&&raster!=null,base.retryEnabled&&raster!=null,base.cancelEnabled,base.backEnabled,raster);
 }
}
