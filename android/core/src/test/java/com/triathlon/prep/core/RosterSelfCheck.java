package com.triathlon.prep.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.hssf.usermodel.*;
import com.triathlon.prep.core.Verification.*;
import com.triathlon.prep.core.Verification.Error;

/** Synthetic fixture checks. Never prints source identifiers or uses hardware/vendor SDKs. */
public final class RosterSelfCheck {
 static int passed;
 static final String ID_A="990101200001010014", ID_B="990101200001010022";
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 interface Test{void run()throws Exception;}
 static void test(String name,Test test)throws Exception{test.run();System.out.println("PASS "+(++passed)+" "+name);}
 static RaceRoster roster(String bib,String name){return new RaceRoster("合成赛事",UUID.randomUUID().toString(),Arrays.asList(new RaceRoster.Entry(ID_A,bib,name,"合成组","")));}
 static byte[] xls(boolean numericId,boolean duplicate,boolean missing)throws Exception{
  try(HSSFWorkbook book=new HSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
   HSSFSheet s=book.createSheet("合成名单");String[] headers={"比赛号码","姓名","证件号码","参赛组别","所属团体"};
   for(int c=0;c<headers.length;c++)s.getRow(0);HSSFRow h=s.createRow(0);for(int c=0;c<headers.length;c++)h.createCell(c).setCellValue(headers[c]);
   for(int n=0;n<(duplicate?2:1);n++){HSSFRow r=s.createRow(n+1);r.createCell(0).setCellValue("001");r.createCell(1).setCellValue(missing?"":"合成甲");if(numericId)r.createCell(2).setCellValue(990101200001010010d);else r.createCell(2).setCellValue(ID_A);r.createCell(3).setCellValue("合成组");}
   book.write(out);return out.toByteArray();
  }
 }
 static ExcelRosterReader.Preview parse(byte[] bytes,String name)throws Exception{return ExcelRosterReader.read(new ByteArrayInputStream(bytes),name,"合成赛事","");}
 static final class Store implements RecordStore {final List<Record> rows=new ArrayList<>();public void save(Record r){rows.add(r);}public List<Record> list(){return rows;}public void clear(){rows.clear();}}
 static final class Clock implements Verification.Clock{public long elapsedMillis(){return 1;}public long wallMillis(){return 1;}}
 static final class Reader implements DeviceAdapter{int captures;Reply<Card> read;public Mode mode(){return Mode.TEST;}public void readCard(String s,Reply<Card> r){read=r;}public void capture(String s,Reply<Capture> r){captures++;r.ok(new Capture(new byte[]{2},1,true));}public void cancel(String s){}}
 static final class Algo implements AlgorithmAdapter{int calls;boolean mismatch;public Mode mode(){return Mode.TEST;}public void compare(String s,byte[] a,byte[] b,Reply<Comparison> r){calls++;r.ok(new Comparison(Liveness.PASSED,mismatch?50d:90d,80d,ScoreRule.AT_LEAST,"SYNTHETIC"));}public void cancel(String s){}}
 static Verification flow(Reader r,Algo a,Store s,RosterProvider p){return new Verification(Mode.TEST,r,a,s,new Clock(),100,p);}
 public static void main(String[] args)throws Exception {
  Path parent=Paths.get(args[0]);Files.createDirectories(parent);Path base=Files.createTempDirectory(parent,"run-");
  test("ID text normalization and checksum/date validation",()->{check(IdentityNumber.normalize(" "+ID_A+" ").equals(ID_A),"space normalization");for(String value:new String[]{"000000000000000001","990101200002300010",ID_A.substring(0,17)+"9","9.901012E17"}){try{IdentityNumber.normalize(value);throw new AssertionError("invalid ID accepted");}catch(IllegalArgumentException expected){}}});
  test("true XLS parses synthetic roster preserving bib text",()->{ExcelRosterReader.Preview p=parse(xls(false,false,false),"synthetic.xls");check(p.errors.isEmpty()&&p.roster.size()==1&&p.roster.match(ID_A).bib.equals("001"),"xls import");});
  test("numeric ID precision is rejected",()->check(!parse(xls(true,false,false),"precision.xls").errors.isEmpty(),"numeric ID accepted"));
  test("duplicate ID/bib and missing field reject entire batch",()->{check(parse(xls(false,true,false),"dup.xls").roster==null,"duplicates");check(parse(xls(false,false,true),"missing.xls").roster==null,"missing");});
  test("non-workbook extension spoof rejected",()->check(parse("姓名,证件号码".getBytes("UTF-8"),"fake.xls").roster==null,"CSV spoof"));
  test("private roster commit and cold reopen",()->{FileRosterStore store=new FileRosterStore(base.resolve("roster").toFile());check(store.current()==null,"initially missing");RaceRoster r=roster("001","合成甲");store.replace(r);check(new FileRosterStore(base.resolve("roster").toFile()).current().batchId.equals(r.batchId),"cold read");});
  test("failed import and expired confirmation preserve valid roster",()->{FileRosterStore store=new FileRosterStore(base.resolve("preview").toFile());RaceRoster original=roster("001","合成甲");store.replace(original);RosterImportSession session=new RosterImportSession(store);ExcelRosterReader.Preview first=session.preview(new ByteArrayInputStream(xls(false,false,false)),"first.xls","合成赛事","");session.preview(new ByteArrayInputStream(xls(false,true,false)),"bad.xls","合成赛事","");try{session.confirm(first.id,true);throw new AssertionError("expired preview committed");}catch(IllegalStateException expected){}check(store.current().batchId.equals(original.batchId),"old roster replaced by failed import");});
  test("active-session import rejected and discarded preview cannot commit",()->{FileRosterStore store=new FileRosterStore(base.resolve("active-import").toFile());RosterImportSession session=new RosterImportSession(store);ExcelRosterReader.Preview p=session.preview(new ByteArrayInputStream(xls(false,false,false)),"new.xls","合成赛事","");try{session.confirm(p.id,false);throw new AssertionError("active commit allowed");}catch(IllegalStateException expected){}session.discard(p.id);try{session.confirm(p.id,true);throw new AssertionError("discarded commit allowed");}catch(IllegalStateException expected){}check(store.current()==null,"unexpected active commit");});
  test("match gate normalizes surrounding spaces plus identity required for event pass",()->{Reader r=new Reader();Algo a=new Algo();Store s=new Store();Verification f=flow(r,a,s,()->roster("001","合成甲"));f.begin();r.read.ok(new Card("合成证件甲"," "+ID_A+" ",new byte[]{1}));check(f.snapshot().result.eventVerdict==Verdict.PASSED&&f.snapshot().athlete.bib.equals("001")&&a.calls==1,"combined result");});
  test("same name outside roster blocks capture and identity algorithm",()->{Reader r=new Reader();Algo a=new Algo();Verification f=flow(r,a,new Store(),()->roster("001","合成甲"));f.begin();r.read.ok(new Card("合成甲",ID_B,new byte[]{1}));check(f.snapshot().result.eventVerdict!=Verdict.PASSED&&r.captures==0&&a.calls==0,"outside must stop");});
  test("missing/corrupt roster cannot pass",()->{for(boolean corrupt:new boolean[]{false,true}){Reader r=new Reader();Algo a=new Algo();Verification f=flow(r,a,new Store(),()->{if(corrupt)throw new IOException("fixture");return null;});f.begin();check(f.snapshot().result.eventVerdict!=Verdict.PASSED&&r.read==null&&a.calls==0,"missing/corrupt");}});
  test("matching roster plus identity mismatch rejected",()->{Reader r=new Reader();Algo a=new Algo();a.mismatch=true;Verification f=flow(r,a,new Store(),()->roster("001","合成甲"));f.begin();r.read.ok(new Card("合成甲",ID_A,new byte[]{1}));check(f.snapshot().result.eventVerdict==Verdict.REJECTED,"identity fail");});
  test("cancel and late read cannot associate another athlete",()->{Reader r=new Reader();Algo a=new Algo();Verification f=flow(r,a,new Store(),()->roster("001","合成甲"));f.begin();Reply<Card> old=r.read;f.cancel();f.next();f.begin();old.ok(new Card("合成甲",ID_A,new byte[]{1}));check(f.snapshot().athlete==null&&a.calls==0,"late read");});
  test("begin pins immutable roster version",()->{RaceRoster first=roster("001","合成甲"),second=roster("002","合成新版");RaceRoster[] current={first};Reader r=new Reader();Algo a=new Algo();Verification f=flow(r,a,new Store(),()->current[0]);f.begin();current[0]=second;r.read.ok(new Card("合成甲",ID_A,new byte[]{1}));check(f.snapshot().result.rosterBatchId.equals(first.batchId)&&f.snapshot().athlete.bib.equals("001"),"batch drift");});
  test("legacy identity result is not event pass",()->{Record legacy=new Record(UUID.randomUUID().toString(),1,Mode.TEST,"—","NONE",Verdict.PASSED,Error.NONE,Liveness.PASSED,90d,80d,ScoreRule.AT_LEAST,"SYNTHETIC");check(legacy.eventVerdict==null&&legacy.membership==Membership.LEGACY_NOT_EVALUATED,"legacy reinterpretation");});
  System.out.println("SUMMARY "+passed+" roster checks; synthetic host-only, no vendor/native");
 }
}
