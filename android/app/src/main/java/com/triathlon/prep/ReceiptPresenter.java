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
   boolean eligible=document!=null&&layout!=null&&printingAllowed;
   return new ReceiptPreviewView.Model(id,simulation,document,status,same?job.failure:ReceiptPrintController.Failure.NONE,
    same?job.jobId:"",!printingAllowed?"打印已关闭或设置未确认，仍可预览。":layout!=null?"":"打印参数未确认，当前仅可预览。",
    eligible&&(status==ReceiptPrintController.Status.IDLE||status==ReceiptPrintController.Status.SIMULATED||status==ReceiptPrintController.Status.ACCEPTED),
    eligible&&(status==ReceiptPrintController.Status.FAILED||status==ReceiptPrintController.Status.CANCELLED),
    document!=null&&status==ReceiptPrintController.Status.SENDING,true);
  }
 }
}
