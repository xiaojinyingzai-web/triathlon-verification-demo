package com.triathlon.prep.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.triathlon.prep.core.Verification.*;
import com.triathlon.prep.core.Verification.Error;

/** LIVE-mode doubles prove gates on the host only; they are never installed as live adapters. */
public final class ReceiptSelfCheck {
 static int passed;static final String A=Prep19SelfCheck.A,B=Prep19SelfCheck.B;
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 interface Test{void run()throws Exception;}static void test(String name,Test body)throws Exception{body.run();System.out.println("PASS "+(++passed)+" "+name);}
 static final class Store implements RecordStore {
  final List<Record> rows=new ArrayList<>();boolean fail;
  public void save(Record r)throws IOException{if(fail)throw new IOException("synthetic failure");if(!rows.contains(r))rows.add(r);}public List<Record> list(){return rows;}public void clear(){rows.clear();}
 }
 static final class Time implements Clock{long now=1;public long elapsedMillis(){return now;}public long wallMillis(){return now;}}
 static final class Reader implements DeviceAdapter {final Mode mode;Reply<Card> read;Reply<Capture> capture;Reader(Mode m){mode=m;}public Mode mode(){return mode;}public void readCard(String id,Reply<Card> reply){read=reply;}public void capture(String id,Reply<Capture> reply){capture=reply;}public void cancel(String id){}}
 static final class Algo implements AlgorithmAdapter{final Mode mode;Reply<Comparison> reply;Algo(Mode m){mode=m;}public Mode mode(){return mode;}public void compare(String id,byte[] chip,byte[] live,Reply<Comparison> r){reply=r;}public void cancel(String id){}}
 static final class Fixture {
  final Reader reader;final Algo algo;final Store store=new Store();final Time clock=new Time();final Verification flow;final RaceRoster roster;String id;
  Fixture(Mode mode,RaceRoster.SwimTest swim){
   reader=new Reader(mode);algo=new Algo(mode);
   roster=new RaceRoster("合成赛事",UUID.randomUUID().toString(),Arrays.asList(new RaceRoster.Entry(A,"001","合成选手甲","合成组","合成团体",swim)));
   flow=new Verification(mode,reader,algo,store,clock,100,()->roster);
  }
  void begin(){id=flow.begin();}void card(){reader.read.ok(new Card("合成证件姓名",A,new byte[]{1}));}void capture(){reader.capture.ok(new Capture(new byte[]{2},1,true));}
  void compare(boolean pass){algo.reply.ok(new Comparison(Liveness.PASSED,pass?90d:50d,80d,ScoreRule.AT_LEAST,"SYNTHETIC_HOST"));}
  void pass(){begin();card();capture();compare(true);}
 }
 static CheckInReceipt.Step swimming(CheckInReceipt.Document d){return d.steps.stream().filter(s->s.item==CheckInReceipt.Item.SWIMMING).findFirst().get();}
 static void denied(Fixture f){check(CheckInReceipt.prepare(f.flow,f.id)==null,"invalid result produced a real receipt");check(CheckInReceipt.preparePreview(f.flow,f.id)==null,"invalid result produced preview");}
 public static void main(String[] args)throws Exception {
  test("current saved roster plus LIVE identity pass creates attributable receipt",()->{Fixture f=new Fixture(Mode.LIVE,RaceRoster.SwimTest.EXPLICIT_BLANK);f.pass();CheckInReceipt.Document d=CheckInReceipt.prepare(f.flow,f.id);check(d!=null,"eligible receipt missing");check(!d.simulation&&d.eventName.equals("合成赛事")&&d.title.equals("报到流程单")&&d.name.equals("合成选手甲")&&d.team.equals("合成团体")&&d.bib.equals("001")&&d.group.equals("合成组")&&d.rosterBatchId.equals(f.roster.batchId)&&d.sessionId.equals(f.id),"wrong person/event");check(CheckInReceipt.canPrint(f.flow,d),"current valid document blocked");});
  test("swim marked is unchecked blank is required unknown asks confirmation",()->{for(RaceRoster.SwimTest state:RaceRoster.SwimTest.values()){Fixture f=new Fixture(Mode.LIVE,state);f.pass();CheckInReceipt.Step s=swimming(CheckInReceipt.prepare(f.flow,f.id));check(s.checked==(state==RaceRoster.SwimTest.EXPLICIT_BLANK),"inverse swimming mark wrong");check(s.meaning==(state==RaceRoster.SwimTest.MARKED?CheckInReceipt.Meaning.NOT_REQUIRED:state==RaceRoster.SwimTest.EXPLICIT_BLANK?CheckInReceipt.Meaning.REQUIRED:CheckInReceipt.Meaning.CONFIRM),"swimming semantics wrong");}});
  test("receipt never fabricates declaration insurance bag completion",()->{Fixture f=new Fixture(Mode.LIVE,RaceRoster.SwimTest.MARKED);f.pass();CheckInReceipt.Document d=CheckInReceipt.prepare(f.flow,f.id);check(d.steps.size()==5,"workflow items missing");for(CheckInReceipt.Step s:d.steps){if(s.item==CheckInReceipt.Item.IDENTITY)check(s.checked&&s.meaning==CheckInReceipt.Meaning.COMPLETED,"identity not verified");else if(s.item!=CheckInReceipt.Item.SWIMMING)check(!s.checked&&s.meaning==CheckInReceipt.Meaning.PENDING,"completion invented");}});
  test("failure outsider pending cancelled and timeout cannot generate receipt",()->{
   Fixture fail=new Fixture(Mode.LIVE,RaceRoster.SwimTest.UNKNOWN);fail.begin();fail.card();fail.capture();fail.compare(false);denied(fail);
   Fixture outside=new Fixture(Mode.LIVE,RaceRoster.SwimTest.UNKNOWN);outside.begin();outside.reader.read.ok(new Card("合成选手甲",B,new byte[]{1}));denied(outside);
   for(int stage=0;stage<3;stage++){Fixture f=new Fixture(Mode.LIVE,RaceRoster.SwimTest.UNKNOWN);f.begin();if(stage>0)f.card();if(stage>1)f.capture();denied(f);f.flow.cancel();denied(f);}
   Fixture timeout=new Fixture(Mode.LIVE,RaceRoster.SwimTest.UNKNOWN);timeout.begin();timeout.clock.now=101;timeout.flow.tick();denied(timeout);
  });
  test("save failure blocks receipt until same-session save succeeds",()->{Fixture f=new Fixture(Mode.LIVE,RaceRoster.SwimTest.UNKNOWN);f.store.fail=true;f.pass();denied(f);f.store.fail=false;f.flow.retrySave();check(CheckInReceipt.prepare(f.flow,f.id)!=null,"save retry not accepted");});
  test("next or retry invalidates old document and stale expected session",()->{Fixture f=new Fixture(Mode.LIVE,RaceRoster.SwimTest.EXPLICIT_BLANK);f.pass();CheckInReceipt.Document d=CheckInReceipt.prepare(f.flow,f.id);String old=f.id;f.flow.next();check(!CheckInReceipt.canPrint(f.flow,d),"old document printable after next");try{CheckInReceipt.render(f.flow,d,new CheckInReceipt.Layout(384,12,24));throw new AssertionError("old document rendered");}catch(IllegalStateException expected){}f.pass();check(CheckInReceipt.prepare(f.flow,old)==null&&!CheckInReceipt.canPrint(f.flow,d),"old session reused");});
  test("a receipt cannot be replayed across controller instances",()->{Fixture a=new Fixture(Mode.LIVE,RaceRoster.SwimTest.MARKED),b=new Fixture(Mode.LIVE,RaceRoster.SwimTest.MARKED);a.pass();b.pass();check(!CheckInReceipt.canPrint(b.flow,CheckInReceipt.prepare(a.flow,a.id)),"cross-controller replay");});
  test("TEST generates marked preview and can never qualify as successful real slip",()->{Fixture f=new Fixture(Mode.TEST,RaceRoster.SwimTest.EXPLICIT_BLANK);f.pass();check(CheckInReceipt.prepare(f.flow,f.id)==null,"TEST accepted as LIVE");CheckInReceipt.Document d=CheckInReceipt.preparePreview(f.flow,f.id);check(d!=null&&d.simulation&&!CheckInReceipt.canPrint(f.flow,d),"simulation masquerades as real");String text=String.join("\n",CheckInReceipt.render(f.flow,d,new CheckInReceipt.Layout(384,12,24)));check(text.contains("模拟")&&text.contains("非有效凭条")&&!text.contains(A)&&!text.contains("********"),"preview privacy/marking");});
  test("logical narrow layout wraps without dropping club name or source fields",()->{Fixture f=new Fixture(Mode.TEST,RaceRoster.SwimTest.UNKNOWN);f.pass();CheckInReceipt.Document d=CheckInReceipt.preparePreview(f.flow,f.id);List<String> lines=CheckInReceipt.render(f.flow,d,new CheckInReceipt.Layout(128,8,16));String text=String.join("",lines).replace(" ","");check(text.contains("合成赛事")&&text.contains("报到流程单")&&text.contains("合成选手甲")&&text.contains("合成团体")&&text.contains("泳池测试")&&!text.contains("待确认")&&swimming(d).meaning==CheckInReceipt.Meaning.CONFIRM,"layout lost content or paper-external meaning");check(lines.size()>10,"narrow content did not wrap");});
  if(args.length>0){Path output=Paths.get(args[0]);Files.createDirectories(output);for(RaceRoster.SwimTest state:RaceRoster.SwimTest.values()){Fixture f=new Fixture(Mode.TEST,state);f.pass();Files.write(output.resolve("receipt-"+state+".txt"),CheckInReceipt.render(f.flow,CheckInReceipt.preparePreview(f.flow,f.id),new CheckInReceipt.Layout(384,12,24)),java.nio.charset.StandardCharsets.UTF_8);}}
  ReceiptLayoutSelfCheck.main(new String[0]);
  System.out.println("SUMMARY "+passed+" receipt checks; all verification observations are synthetic doubles");
 }
}
