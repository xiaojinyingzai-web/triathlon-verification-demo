package com.triathlon.prep.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** Synthetic event-layout, persistence and receipt checks; no real personal data or hardware. */
public final class Prep19SelfCheck {
 static {System.setProperty("log4j2.loggerContextFactory","org.apache.logging.log4j.simple.SimpleLoggerContextFactory");System.setProperty("log4j2.simplelogLevel","OFF");System.setProperty("log4j2.statusLoggerLevel","OFF");}
 static final String A="990101200001010014",B="990101200001010022",C="990101200001010030";
 static final String[] HEADERS={"项目","组别","出发号","姓名","性别","出生日期","证件类型","证件号码","电话","服装尺码","紧急联系人","紧急联系电话","所属团体会员","游泳测试","年费支付状态"};
 static int passed;
 interface Test {void run()throws Exception;}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void test(String name,Test body)throws Exception{body.run();System.out.println("PASS "+(++passed)+" "+name);}
 static byte[] xls(String variant)throws Exception {
  try(HSSFWorkbook w=new HSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
   HSSFSheet s=w.createSheet("合成名单");int offset=variant.equals("titles")?2:0;
   if(offset>0){s.createRow(0).createCell(0).setCellValue("合成赛事（测试夹具）");s.createRow(1).createCell(0).setCellValue("合成标题第二行");s.addMergedRegion(new CellRangeAddress(0,0,0,14));s.addMergedRegion(new CellRangeAddress(1,1,0,14));}
   HSSFRow h=s.createRow(offset);for(int c=0;c<15;c++){if(variant.equals("no-swim")&&c==13)continue;h.createCell(c).setCellValue(HEADERS[c]);}
   for(int n=0;n<3;n++){
    HSSFRow r=s.createRow(offset+n+1);r.createCell(0).setCellValue("合成项目");r.createCell(1).setCellValue(n==0?"合成组甲":"合成组乙");r.createCell(2).setCellValue(variant.equals("duplicate-bib")?"001":String.format(Locale.ROOT,"%03d",n+1));r.createCell(3).setCellValue(variant.equals("missing-name")&&n==1?"":"合成选手"+n);
    if(variant.equals("numeric-id")&&n==1)r.createCell(7).setCellValue(990101200001010000d);else r.createCell(7).setCellValue(variant.equals("duplicate-id")?A:new String[]{A,B,C}[n]);
    r.createCell(12).setCellValue(n==2?"":"合成团体"+n);
    if(n==0)r.createCell(13).setCellValue("√");if(n==1)r.createCell(13).setCellValue("");if(n==2)r.createCell(13).setCellValue("待确认");
    if(variant.equals("formula-swim")&&n==0)r.getCell(13).setCellFormula("1+1");
    if(variant.equals("numeric-swim")&&n==0)r.getCell(13).setCellValue(1d);
   }
   w.write(out);return out.toByteArray();
  }
 }
 static ExcelRosterReader.Preview read(byte[] bytes,String file){return ExcelRosterReader.read(new ByteArrayInputStream(bytes),file,"合成赛事","");}
 static RaceRoster parsed(String variant)throws Exception {ExcelRosterReader.Preview p=read(xls(variant),"synthetic.xls");check(p.roster!=null,"event layout must import, errors="+p.errors.size());return p.roster;}
 static String swim(RaceRoster.Athlete athlete){return athlete.swimTest.toString();}
 static void legacy(File file)throws Exception {
  ByteArrayOutputStream body=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(body);
  out.writeInt(0x52535431);out.writeInt(1);out.writeUTF("合成旧赛事");out.writeUTF("11111111-1111-1111-1111-111111111111");out.writeInt(1);out.writeUTF(A);out.writeUTF("007");out.writeUTF("合成旧选手");out.writeUTF("合成旧组");out.writeUTF("合成旧团体");out.flush();
  CRC32 crc=new CRC32();crc.update(body.toByteArray());out.writeLong(crc.getValue());Files.write(file.toPath(),body.toByteArray());
 }
 public static void main(String[] args)throws Exception {
  test("event 15-column XLS maps bib/team/group without extra fields",()->{RaceRoster r=parsed("normal");check(r.size()==3&&r.match(A).bib.equals("001")&&r.match(A).team.equals("合成团体0")&&r.match(B).group.equals("合成组乙"),"event fields lost");});
  test("multirow titles before exact event headers import",()->check(parsed("titles").size()==3,"titles treated as athletes"));
  test("explicit marked/blank/unknown source states remain distinct",()->{RaceRoster r=parsed("normal");check(swim(r.match(A)).equals("MARKED"),"marked test lost");check(swim(r.match(B)).equals("EXPLICIT_BLANK"),"explicit blank lost");check(swim(r.match(C)).equals("UNKNOWN"),"unknown test guessed");});
  test("missing swim column remains unknown even if an unheaded cell has a mark",()->check(swim(parsed("no-swim").match(A)).equals("UNKNOWN"),"missing column guessed"));
  test("formula/numeric swim values never become exempt or required",()->{check(swim(parsed("formula-swim").match(A)).equals("UNKNOWN"),"formula evaluated");check(swim(parsed("numeric-swim").match(A)).equals("UNKNOWN"),"number guessed");});
  test("duplicate IDs/bibs and missing names reject all rows",()->{for(String v:new String[]{"duplicate-id","duplicate-bib","missing-name"})check(read(xls(v),"bad.xls").roster==null,"bad batch accepted: "+v);});
  test("numeric identity loses precision and rejects batch",()->{ExcelRosterReader.Preview p=read(xls("numeric-id"),"bad.xls");check(p.roster==null&&p.errors.stream().anyMatch(e->e.code==ExcelRosterReader.Code.LOST_PRECISION),"numeric ID accepted");});
  test("event title is required context; project/group columns cannot invent it",()->check(ExcelRosterReader.read(new ByteArrayInputStream(xls("normal")),"synthetic.xls","","").roster==null,"event guessed"));
  if(args.length>0){test("same-layout artifact XLSX preserves all three swim states",()->{RaceRoster r=read(Files.readAllBytes(Paths.get(args[0])),"synthetic.xlsx").roster;check(r!=null&&r.size()==3&&r.match(A).bib.equals("001")&&r.match(A).team.equals("合成团体甲")&&swim(r.match(A)).equals("MARKED")&&swim(r.match(B)).equals("EXPLICIT_BLANK")&&swim(r.match(C)).equals("UNKNOWN"),"XLSX fields differ");});}
  Path base=Files.createTempDirectory("prep19-synthetic-");
  test("new snapshot cold reopen preserves all fields and three states",()->{File dir=base.resolve("new").toFile();FileRosterStore s=new FileRosterStore(dir);RaceRoster r=parsed("normal");s.replace(r);RaceRoster cold=new FileRosterStore(dir).current();check(cold.batchId.equals(r.batchId)&&cold.eventName.equals("合成赛事")&&cold.match(A).bib.equals("001")&&cold.match(A).name.equals("合成选手0")&&cold.match(A).group.equals("合成组甲")&&cold.match(A).team.equals("合成团体0"),"fields changed");check(swim(cold.match(A)).equals("MARKED")&&swim(cold.match(B)).equals("EXPLICIT_BLANK")&&swim(cold.match(C)).equals("UNKNOWN"),"stored swimming state lost");});
  test("legacy version one reads unknown without silently rewriting bytes",()->{File dir=base.resolve("legacy").toFile();FileRosterStore s=new FileRosterStore(dir);File file=new File(dir,"roster.bin");legacy(file);byte[] before=Files.readAllBytes(file.toPath());RaceRoster r=s.current();check(r.match(A).bib.equals("007")&&r.match(A).team.equals("合成旧团体")&&swim(r.match(A)).equals("UNKNOWN"),"legacy reinterpreted");check(Arrays.equals(before,Files.readAllBytes(file.toPath())),"read migrated bytes");s.replace(parsed("normal"));check(swim(s.current().match(A)).equals("MARKED"),"validated replacement failed");});
  test("failed validated replacement preserves version-one roster bytes",()->{File dir=base.resolve("failure").toFile();FileRosterStore s=new FileRosterStore(dir);File file=new File(dir,"roster.bin");legacy(file);byte[] before=Files.readAllBytes(file.toPath());File blocker=new File(dir,"roster.bin.new");check(blocker.mkdir(),"fixture mkdir");Files.write(new File(blocker,"block").toPath(),new byte[]{1});try{s.replace(parsed("normal"));throw new AssertionError("write failure hidden");}catch(IOException expected){}check(Arrays.equals(before,Files.readAllBytes(file.toPath())),"valid roster overwritten");});
  test("invalid import never replaces valid current list",()->{FileRosterStore s=new FileRosterStore(base.resolve("reject").toFile());RaceRoster r=parsed("normal");s.replace(r);RosterImportSession importer=new RosterImportSession(s);ExcelRosterReader.Preview bad=importer.preview(new ByteArrayInputStream(xls("duplicate-id")),"bad.xls","合成赛事","");try{importer.confirm(bad.id,true);throw new AssertionError("bad committed");}catch(IllegalStateException expected){}check(s.current().batchId.equals(r.batchId)&&swim(s.current().match(A)).equals("MARKED"),"list replaced");});
  if(args.length>0){Path fixtures=Paths.get(args[0]).getParent();Files.write(fixtures.resolve("synthetic-roster.xls"),xls("normal"));Files.write(fixtures.resolve("synthetic-roster-titles.xls"),xls("titles"));}
  System.out.println("SUMMARY "+passed+" PREP19 checks; synthetic host-only");
 }
}
