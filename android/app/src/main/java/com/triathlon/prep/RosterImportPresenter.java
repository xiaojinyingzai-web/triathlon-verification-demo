package com.triathlon.prep;

import java.util.*;
import com.triathlon.prep.core.*;
import com.triathlon.prep.ui.RosterImportView;

/** Typed safe row reasons only. Never projects document keys or raw cells to UI. */
final class RosterImportPresenter {
 static RosterImportView.Model model(boolean simulation,RosterImportView.State state,RaceRoster current,boolean corrupt,
         ExcelRosterReader.Preview pending,String event,String fallback,int page,String message,boolean replaceAllowed){
  List<RosterImportView.RowError> errors=new ArrayList<>();int count=pending==null?0:pending.errors.size(),pages=(count+19)/20;
  page=pages==0?0:Math.max(0,Math.min(page,pages-1));
  if(pending!=null)for(int i=page*20;i<Math.min(count,(page+1)*20);i++){ExcelRosterReader.Problem e=pending.errors.get(i);errors.add(new RosterImportView.RowError(e.sheet,e.row,RosterImportView.Field.valueOf(e.field.name()),RosterImportView.ErrorCode.valueOf(e.code.name())));}
  RosterImportView.BatchSummary active=current==null?null:new RosterImportView.BatchSummary(current.eventName,current.batchId,"本地已保存名单",current.size(),current.size(),0);
  RosterImportView.BatchSummary candidate=pending==null?null:new RosterImportView.BatchSummary(pending.eventName,pending.id,"Excel名单文件",pending.totalRows,pending.validRows,pending.errorRows);
  boolean busy=state==RosterImportView.State.READING||state==RosterImportView.State.SAVING;
  return new RosterImportView.Model(simulation,state,corrupt?RosterImportView.CurrentStatus.CORRUPT:current==null?RosterImportView.CurrentStatus.NONE:RosterImportView.CurrentStatus.VALID,
          active,candidate,pending==null?null:pending.id,event,fallback,message,errors,page,pages,count,
          !busy&&replaceAllowed,!busy&&replaceAllowed&&state==RosterImportView.State.REVIEW&&pending!=null&&pending.roster!=null,
          !busy&&pending!=null,!busy);
 }
}
