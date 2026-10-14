package com.triathlon.prep.core;

import java.io.*;import java.util.*;import java.time.*;import java.time.format.DateTimeFormatter;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;

/** A local, mode/batch-pinned export of committed records. Never accepts UI session results. */
public final class RosterStatusExport {
 private RosterStatusExport(){}
 public enum Status { VERIFIED, UNVERIFIED, PENDING_REVIEW }
 public static final class Row {
  public final RaceRoster.Athlete athlete;public final Status status;public final Verification.Record latest;public final ManualRecord manualLatest;public final boolean checkedIn;
  private Row(RaceRoster.Athlete athlete,Status status,Verification.Record latest,ManualRecord manual,boolean checkedIn){this.athlete=athlete;this.status=status;this.latest=latest;manualLatest=manual;this.checkedIn=checkedIn;}
 }
 public static final class Snapshot {
  public final String eventName,batchId;public final Verification.Mode mode;public final long capturedAt;
  public final List<Row> rows;public final int unlinkedHistoryCount,completedCount,pendingCount,recordCount,automaticLimit,manualLimit;public final int checkedInCount;
  private Snapshot(RaceRoster roster,Verification.Mode mode,long time,List<Row> rows,int missing,int completed,int pending,int recordCount,int automaticLimit,int manualLimit){
   eventName=roster.eventName;batchId=roster.batchId;this.mode=mode;capturedAt=time;this.rows=Collections.unmodifiableList(rows);unlinkedHistoryCount=missing;completedCount=completed;pendingCount=pending;this.recordCount=recordCount;this.automaticLimit=automaticLimit;this.manualLimit=manualLimit;int registered=0;for(Row r:rows)if(r.checkedIn)registered++;checkedInCount=registered;
  }
  public String summary(){return (mode==Verification.Mode.TEST?"TEST虚构测试 · ":"")+"本机 / 当前名单批次："+rows.size()+"人；已核验"+completedCount+"，成功报到"+checkedInCount+"，未完成"+(rows.size()-completedCount-pendingCount)+"，待核对"+pendingCount+"。"+(unlinkedHistoryCount>0?"历史记录无法关联"+unlinkedHistoryCount+"条；时间/结果仅为最近可准确关联完成。":"")+" 本机自动日志上限"+automaticLimit+"条（含重复/取消/失败），人工日志独立上限"+manualLimit+"条，容量满会阻止保存；只统计已保存完成。";}
 }
 /** Caller supplies a successful RecordStore.list snapshot; an IO failure must not become an empty list. */
 public static Snapshot capture(RaceRoster roster,List<Verification.Record> committed,Verification.Mode mode,long capturedAt){
  return capture(roster,committed,Collections.emptyList(),mode,capturedAt);
 }
 public static Snapshot capture(RaceRoster roster,List<Verification.Record> committed,List<ManualRecord> manualRecords,Verification.Mode mode,long capturedAt){
  if(committed==null||manualRecords==null)throw new IllegalArgumentException("Committed history required");
  return captureResolved(roster,committed,manualRecords,mode,capturedAt,0,committed.size()+manualRecords.size(),FileRecordStore.MAX_RECORDS,FileManualRecordStore.MAX_RECORDS);
 }
 /** Indexed adapters pass only current attribution plus authoritative historical totals, not full ledgers. */
 public static Snapshot captureResolved(RaceRoster roster,List<Verification.Record> committed,List<ManualRecord> manualRecords,Verification.Mode mode,long capturedAt,int inheritedMissing,int total,int autoLimit,int manualLimit){
  return captureResolved(roster,committed,manualRecords,mode,capturedAt,inheritedMissing,total,autoLimit,manualLimit,Collections.emptySet());
 }
 public static Snapshot captureResolved(RaceRoster roster,List<Verification.Record> committed,List<ManualRecord> manualRecords,Verification.Mode mode,long capturedAt,int inheritedMissing,int total,int autoLimit,int manualLimit,Set<String> previouslyRegistered){
  if(roster==null||committed==null||manualRecords==null||mode==null)throw new IllegalArgumentException("Current roster and committed history required");
  Set<String> registered=new HashSet<>(previouslyRegistered);Map<String,Verification.Record> latest=new HashMap<>();Set<String> bibs=new HashSet<>();for(RaceRoster.Entry e:roster.storedEntries())bibs.add(e.athlete.bib);
  if(inheritedMissing<0||total<0||autoLimit<1||manualLimit<1)throw new IllegalArgumentException("Storage totals required");int missing=inheritedMissing;Set<String> seen=new HashSet<>();
  for(Verification.Record r:new ArrayList<>(committed)){
   if(r==null||r.mode==null)throw new IllegalArgumentException("Malformed committed record");
   if(r.mode!=mode)continue;
   if(!seen.add(r.sessionId))throw new IllegalArgumentException("Duplicate session in committed history");
   if(r.verdict!=Verification.Verdict.PASSED&&r.verdict!=Verification.Verdict.REJECTED)continue;
   if(r.rosterBatchId.isEmpty()){if(r.membership==Verification.Membership.LEGACY_NOT_EVALUATED||r.membership==Verification.Membership.MATCHED)missing++;continue;}
   if(!r.rosterBatchId.isEmpty()&&!r.rosterBatchId.equals(roster.batchId))continue;
   if(r.membership==Verification.Membership.LEGACY_NOT_EVALUATED){missing++;continue;}
   if(r.membership!=Verification.Membership.MATCHED)continue;
   if(r.rosterBib.isEmpty()||!bibs.contains(r.rosterBib)){missing++;continue;}
   if(CheckIn.trustedSuccess(r))registered.add(r.rosterBib);
   Verification.Record previous=latest.get(r.rosterBib);
   if(previous==null||r.timestamp>previous.timestamp||(r.timestamp==previous.timestamp&&r.sessionId.compareTo(previous.sessionId)>0))latest.put(r.rosterBib,r);
  }
  Map<String,ManualRecord> manual=new HashMap<>();Set<String> manualIds=new HashSet<>(),manualKeys=new HashSet<>();
  for(ManualRecord r:new ArrayList<>(manualRecords)){if(r==null)throw new IllegalArgumentException("Malformed committed manual record");if(r.mode!=mode)continue;if(!manualIds.add(r.id)||!manualKeys.add(r.key()))throw new IllegalArgumentException("Duplicate committed manual result");if(!r.rosterBatchId.equals(roster.batchId))continue;if(!bibs.contains(r.rosterBib)){missing++;continue;}manual.put(r.rosterBib,r);registered.add(r.rosterBib);}
  List<Row> rows=new ArrayList<>();int completed=0,pending=0;
  for(RaceRoster.Entry e:roster.storedEntries()){
   Verification.Record r=latest.get(e.athlete.bib);ManualRecord m=manual.get(e.athlete.bib);
   if(m!=null&&r!=null){if(m.timestamp>r.timestamp||m.timestamp==r.timestamp&&m.id.compareTo(r.sessionId)>=0)r=null;else m=null;}
   Status status=r!=null||m!=null?Status.VERIFIED:missing>0?Status.PENDING_REVIEW:Status.UNVERIFIED;
   if(r!=null||m!=null)completed++;else if(missing>0)pending++;
   rows.add(new Row(e.athlete,status,r,m,registered.contains(e.athlete.bib)));
  }
  return new Snapshot(roster,mode,capturedAt,rows,missing,completed,pending,total,autoLimit,manualLimit);
 }
 private static String time(long timestamp){return DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX",Locale.ROOT).format(Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.ofHours(8)));}
 /** Writes genuine OLE Excel .xls. Caller owns output closure and decides success only after close. */
 public static void writeXls(Snapshot snapshot,OutputStream output)throws IOException{
  if(snapshot==null||output==null)throw new IllegalArgumentException("Snapshot/output required");
  try(HSSFWorkbook book=new HSSFWorkbook()){
   CellStyle text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
   Sheet sheet=book.createSheet("名单核验状态");String[] header={"比赛号码","姓名","参赛组别","所属团体","游泳测试注记","核验状态","核验结果（最近关联完成）","最后关联完成时间（北京时间）","数据模式","范围/历史说明","核验方式","成功报到"};
   cells(sheet.createRow(0),header,text);int index=1;
   for(Row row:snapshot.rows){RaceRoster.Athlete a=row.athlete;Verification.Record r=row.latest;ManualRecord m=row.manualLatest;
    cells(sheet.createRow(index++),new String[]{a.bib,a.name,a.group,a.team,a.swimTest==RaceRoster.SwimTest.MARKED?"名单已标记":a.swimTest==RaceRoster.SwimTest.EXPLICIT_BLANK?"名单明确空白":"名单未提供",
     row.status==Status.VERIFIED?"已核验":row.status==Status.PENDING_REVIEW?"待核对":"未完成核验",m!=null?"人工已通过":r==null?"":r.verdict==Verification.Verdict.PASSED?"通过":"不通过",m!=null?time(m.timestamp):r==null?"":time(r.timestamp),snapshot.mode==Verification.Mode.TEST?"TEST（虚构测试，非有效核验）":"LIVE",
     "本机 / 当前名单批次"+(snapshot.unlinkedHistoryCount>0?"；历史记录无法关联，已知结果仅最近可关联完成":""),m!=null?"MANUAL（人工核验）":r!=null?"AUTOMATIC（自动核验）":"",row.checkedIn?"是":"否"},text);
   }
   sheet.createFreezePane(0,1);for(int i=0;i<header.length;i++)sheet.setColumnWidth(i,(i==9?55:i==7?30:22)*256);
   Sheet note=book.createSheet("导出说明");String[][] notes={{"范围","本机 / 当前名单批次；两台设备尚无同步，不代表全赛事"},{"赛事",snapshot.eventName},{"名单批次",snapshot.batchId},{"数据模式",snapshot.mode==Verification.Mode.TEST?"TEST虚构测试，非真实身份核验结果":"LIVE"},{"快照时间（北京时间）",time(snapshot.capturedAt)},{"统计",snapshot.summary()},{"成功报到定义","当前可信批次人员曾已保存自动PASSED或人工CONFIRMED；同人只计一次，后续失败/打印失败不撤销"},{"完成定义","准确关联且已保存的自动通过/不通过或人工CONFIRMED；取消、无法核验和保存失败不算已完成"},{"未完成定义","本机该批次没有可准确关联的已保存完成记录，不等于从未刷卡或其他设备未核验"},{"历史覆盖","旧记录不按姓名/证件尾号推断；有可能属于本批次的旧完成记录使未确认行待核对"},{"最近规则","完成时间最大；同时间按session标识字典序确定；较新取消/无法核验不覆盖完成"},{"游泳注记","来自名单源标记，非本次游泳测试合格证据"},{"隐私","不导出完整证件号码、脱敏尾号或人脸照片"},{"日志容量","自动日志上限"+snapshot.automaticLimit+"条，人工日志上限"+snapshot.manualLimit+"条；容量满阻保存，不截断。人工补打不新增完成记录/时间"}};
   for(int i=0;i<notes.length;i++)cells(note.createRow(i),notes[i],text);note.setColumnWidth(0,24*256);note.setColumnWidth(1,100*256);book.write(output);
  }
 }
 private static void cells(org.apache.poi.ss.usermodel.Row row,String[] values,CellStyle style){for(int i=0;i<values.length;i++){Cell cell=row.createCell(i,CellType.STRING);cell.setCellStyle(style);cell.setCellValue(values[i]);}}
}

