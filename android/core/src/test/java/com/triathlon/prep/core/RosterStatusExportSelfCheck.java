package com.triathlon.prep.core;

import java.io.*;import java.lang.reflect.*;import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;import org.apache.poi.ss.usermodel.CellType;

/** Real records/storage/export; synthetic names and controlled persistence faults only. */
public final class RosterStatusExportSelfCheck {
 static int passed;
 static final String BATCH="00000000-0000-0000-0000-000000000029",OTHER="00000000-0000-0000-0000-000000000028",LONG="000000000000000002";
 interface Test{void run()throws Exception;}static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 static void test(String name,Test t)throws Exception{t.run();System.out.println("PASS "+(++passed)+" "+name);}
 static Object field(Object o,String n)throws Exception{try{return o.getClass().getField(n).get(o);}catch(NoSuchFieldException ex){throw new AssertionError("Missing durable roster association/export field: "+n,ex);}}
 static Verification.Record row(int id,long time,Verification.Mode mode,String batch,String bib,Verification.Verdict verdict)throws Exception{
  Class<?>[] types={String.class,long.class,Verification.Mode.class,String.class,String.class,Verification.Verdict.class,Verification.Error.class,Verification.Liveness.class,Double.class,Double.class,Verification.ScoreRule.class,String.class,String.class,Verification.Membership.class,String.class};
  try{return (Verification.Record)Verification.Record.class.getConstructor(types).newInstance(String.format("00000000-0000-0000-0000-%012d",id),time,mode,"**************0014","TEST_FIXTURE_PAIR",verdict,verdict==Verification.Verdict.PASSED?Verification.Error.NONE:Verification.Error.NO_MATCH,Verification.Liveness.PASSED,90d,80d,Verification.ScoreRule.AT_LEAST,"SYNTHETIC29",batch,Verification.Membership.MATCHED,bib);}catch(NoSuchMethodException ex){throw new AssertionError("Missing durable batch+bib record constructor",ex);}
 }
 static RaceRoster roster(){return new RaceRoster("合成赛事",BATCH,Arrays.asList(new RaceRoster.Entry("990101200001010014","001","同名合成","=1+1","@合成队",RaceRoster.SwimTest.MARKED),new RaceRoster.Entry("990101200001010022",LONG,"同名合成","合成组","合成队",RaceRoster.SwimTest.EXPLICIT_BLANK),new RaceRoster.Entry("990101200001010030","003","合成第三人","合成组","",RaceRoster.SwimTest.UNKNOWN)));}
 static Class<?> exporter()throws Exception{try{return Class.forName("com.triathlon.prep.core.RosterStatusExport");}catch(ClassNotFoundException ex){throw new AssertionError("Missing full roster status exporter",ex);}}
 static Object capture(RaceRoster roster,List<Verification.Record> records,Verification.Mode mode)throws Exception{return exporter().getMethod("capture",RaceRoster.class,List.class,Verification.Mode.class,long.class).invoke(null,roster,records,mode,5000L);}
 static Object member(Object snapshot,String bib)throws Exception{for(Object r:(List<?>)field(snapshot,"rows"))if(((RaceRoster.Athlete)field(r,"athlete")).bib.equals(bib))return r;throw new AssertionError("Missing full roster member");}
 static String status(Object s,String bib)throws Exception{return field(member(s,bib),"status").toString();}
 static void write(Object s,OutputStream out)throws Exception{exporter().getMethod("writeXls",s.getClass(),OutputStream.class).invoke(null,s,out);}
 static void legacyFixture(File dir,int version,int count)throws Exception{
  dir.mkdirs();java.util.zip.CRC32 crc=new java.util.zip.CRC32();
  try(DataOutputStream out=new DataOutputStream(new java.util.zip.CheckedOutputStream(new FileOutputStream(new File(dir,"records.bin")),crc))){
   out.writeInt(0x54505331);out.writeInt(version);out.writeInt(count);
   for(int i=0;i<count;i++){out.writeUTF(String.format("00000000-0000-0000-0000-%012d",100+i));out.writeLong(i);out.writeUTF("TEST");out.writeUTF("**************0014");out.writeUTF("TEST_FIXTURE_PAIR");out.writeUTF("PASSED");out.writeUTF("NONE");out.writeUTF("PASSED");out.writeBoolean(true);out.writeDouble(90);out.writeBoolean(true);out.writeDouble(80);out.writeBoolean(true);out.writeUTF("AT_LEAST");out.writeUTF("SYNTHETIC29");if(version==3){out.writeUTF(BATCH);out.writeUTF("MATCHED");}}
   out.writeLong(crc.getValue());
  }
 }
 public static void main(String[] args)throws Exception{
  File dir=new File(args[0]);dir.mkdirs();
  test("real matched flow persists pinned batch+bib without full identity",()->{
   AutoPrintPolicySelfCheck.Store ps=new AutoPrintPolicySelfCheck.Store();AutoPrintPolicySelfCheck.Fixture f=new AutoPrintPolicySelfCheck.Fixture(Verification.Mode.TEST,ps);f.flow.begin();f.pass();
   check(field(f.flow.snapshot().result,"rosterBib").equals("001"),"matched flow must retain exact bib");check(!f.flow.snapshot().result.rosterBatchId.isEmpty(),"pinned batch required");
  });
  test("same suffix/name never joins other bib batch or mode",()->{
   List<Verification.Record> rows=Arrays.asList(row(1,100,Verification.Mode.TEST,BATCH,"001",Verification.Verdict.PASSED),row(2,200,Verification.Mode.TEST,OTHER,LONG,Verification.Verdict.PASSED),row(3,300,Verification.Mode.LIVE,BATCH,LONG,Verification.Verdict.PASSED));Object s=capture(roster(),rows,Verification.Mode.TEST);
   check(((List<?>)field(s,"rows")).size()==3,"entire roster retained");check(status(s,"001").equals("VERIFIED")&&status(s,LONG).equals("UNVERIFIED")&&status(s,"003").equals("UNVERIFIED"),"exact association isolation");
  });
  test("latest completion ignores later cancelled/unavailable; timestamp tie deterministic",()->{
   List<Verification.Record> rows=Arrays.asList(row(7,200,Verification.Mode.TEST,BATCH,"001",Verification.Verdict.REJECTED),row(9,400,Verification.Mode.TEST,BATCH,"001",Verification.Verdict.UNAVAILABLE),row(6,200,Verification.Mode.TEST,BATCH,"001",Verification.Verdict.PASSED),row(8,300,Verification.Mode.TEST,BATCH,"001",Verification.Verdict.CANCELLED));
   Object a=capture(roster(),rows,Verification.Mode.TEST);Collections.reverse(rows);Object b=capture(roster(),rows,Verification.Mode.TEST);check(((Verification.Record)field(member(a,"001"),"latest")).verdict==Verification.Verdict.REJECTED&&((Verification.Record)field(member(b,"001"),"latest")).sessionId.endsWith("000000000007"),"max timestamp then session key, not input order");
  });
  test("legacy current or unknown batch creates honest coverage gap, other contexts do not",()->{
   Verification.Record current=new Verification.Record("00000000-0000-0000-0000-000000000010",10,Verification.Mode.TEST,"**************0014","TEST_FIXTURE_PAIR",Verification.Verdict.PASSED,Verification.Error.NONE,Verification.Liveness.PASSED,90d,80d,Verification.ScoreRule.AT_LEAST,"SYNTHETIC29",BATCH,Verification.Membership.MATCHED);
   Object s=capture(roster(),Arrays.asList(current,row(11,20,Verification.Mode.TEST,BATCH,"001",Verification.Verdict.PASSED)),Verification.Mode.TEST);
   check(status(s,"001").equals("VERIFIED")&&status(s,LONG).equals("PENDING_REVIEW")&&(Integer)field(s,"unlinkedHistoryCount")==1,"never assign old suffix or claim whole roster unverified");
   Verification.Record unknown=new Verification.Record("00000000-0000-0000-0000-000000000012",10,Verification.Mode.TEST,"**************0014","TEST_FIXTURE_PAIR",Verification.Verdict.REJECTED,Verification.Error.NO_MATCH,Verification.Liveness.PASSED,70d,80d,Verification.ScoreRule.AT_LEAST,"SYNTHETIC29");
   check(status(capture(roster(),Arrays.asList(unknown),Verification.Mode.TEST),"001").equals("PENDING_REVIEW"),"unknown legacy batch cannot be excluded");
   check((Integer)field(capture(roster(),Arrays.asList(row(13,10,Verification.Mode.LIVE,BATCH,"",Verification.Verdict.PASSED),row(14,10,Verification.Mode.TEST,OTHER,"",Verification.Verdict.PASSED)),Verification.Mode.TEST),"unlinkedHistoryCount")==0,"other mode/batch excluded");
  });
  test("new store restart retains association and old fixed27 stays unmapped in v4 copy",()->{
   FileRecordStore store=new FileRecordStore(new File(dir,"new"));store.save(row(15,10,Verification.Mode.TEST,BATCH,LONG,Verification.Verdict.PASSED));List<Verification.Record> reread=new FileRecordStore(new File(dir,"new")).list();check(field(reread.get(0),"rosterBib").equals(LONG),"new association durable");
   File oldDir=new File(args[1]);if(!new File(oldDir,"records.bin").exists())legacyFixture(oldDir,3,27);List<Verification.Record> old=new FileRecordStore(oldDir).list();check(old.size()==27,"read compatible legacy27 copy");for(Verification.Record r:old)check(field(r,"rosterBib").equals(""),"must not infer legacy bib");
   File migrated=new File(dir,"migrated");FileRecordStore changed=new FileRecordStore(migrated);for(Verification.Record r:old)changed.save(r);List<Verification.Record> after=new FileRecordStore(migrated).list();
   for(int i=0;i<old.size();i++)for(Field f:Verification.Record.class.getFields())check(Objects.equals(f.get(old.get(i)),f.get(after.get(i))),"old public fields/order preserved in version4");
  });
  test("real XLS reads full rows with literal formulas leading zero long bib and no document key",()->{
   Object s=capture(roster(),Arrays.asList(row(16,100,Verification.Mode.TEST,BATCH,LONG,Verification.Verdict.REJECTED)),Verification.Mode.TEST);ByteArrayOutputStream out=new ByteArrayOutputStream();write(s,out);byte[] data=out.toByteArray();check(data.length>512&&(data[0]&255)==0xD0&&(data[1]&255)==0xCF,"real OLE XLS, not disguised CSV");
   try(HSSFWorkbook workbook=new HSSFWorkbook(new ByteArrayInputStream(data))){org.apache.poi.ss.usermodel.Sheet sheet=workbook.getSheet("名单核验状态");check(sheet.getLastRowNum()==3,"all3 roster rows independent of logs");check(sheet.getRow(1).getCell(0).getStringCellValue().equals("001")&&sheet.getRow(2).getCell(0).getStringCellValue().equals(LONG),"text precision retained");check(sheet.getRow(1).getCell(2).getCellType()==CellType.STRING&&sheet.getRow(1).getCell(2).getStringCellValue().equals("=1+1"),"formula payload remains a string");String all="";for(org.apache.poi.ss.usermodel.Sheet sh:workbook)for(org.apache.poi.ss.usermodel.Row r:sh)for(org.apache.poi.ss.usermodel.Cell c:r)all+=c.toString();check(all.contains("TEST")&&all.contains("本机")&&!all.contains("990101200001010014")&&!all.contains("**************"),"watermark/scope and no identity export");}
  });
  test("snapshot stays immutable and output faults surface instead of success",()->{
   List<Verification.Record> mutable=new ArrayList<>();Object s=capture(roster(),mutable,Verification.Mode.TEST);mutable.add(row(17,100,Verification.Mode.TEST,BATCH,"001",Verification.Verdict.PASSED));check(status(s,"001").equals("UNVERIFIED"),"fixed IO snapshot");
   try{write(s,new OutputStream(){public void write(int b)throws IOException{throw new IOException("synthetic writer fault");}public void write(byte[] b,int off,int len)throws IOException{throw new IOException("synthetic writer fault");}});throw new AssertionError("write failure swallowed");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IOException,"IO cause retained");}
   try{capture(null,Collections.emptyList(),Verification.Mode.TEST);throw new AssertionError("missing roster exported");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException,"no roster blocks");}
  });
  test("empty batch can never link by bib alone",()->{Object s=capture(roster(),Arrays.asList(row(18,100,Verification.Mode.TEST,"","001",Verification.Verdict.PASSED)),Verification.Mode.TEST);check(status(s,"001").equals("PENDING_REVIEW"),"missing batch must remain unlinked, never current completed");});
  test("v2 migration and capacity failures retain history and cannot mark completion",()->{
   File v2=new File(dir,"version2");legacyFixture(v2,2,1);List<Verification.Record> r=new FileRecordStore(v2).list();check(r.get(0).rosterBatchId.isEmpty()&&field(r.get(0),"rosterBib").equals(""),"v2 cannot invent association");
   File full=new File(dir,"full");legacyFixture(full,3,1000);FileRecordStore store=new FileRecordStore(full);byte[] before=java.nio.file.Files.readAllBytes(new File(full,"records.bin").toPath());
   try{store.save(row(9999,9999,Verification.Mode.TEST,BATCH,LONG,Verification.Verdict.PASSED));throw new AssertionError("capacity must block save");}catch(IOException expected){}
   check(Arrays.equals(before,java.nio.file.Files.readAllBytes(new File(full,"records.bin").toPath()))&&store.list().size()==1000,"capacity does not truncate or commit failed association");check(status(capture(roster(),store.list(),Verification.Mode.TEST),LONG).equals("PENDING_REVIEW"),"capacity-failed completion absent from committed export");
  });
  System.out.println("PASS_TOTAL="+passed);
 }
}
