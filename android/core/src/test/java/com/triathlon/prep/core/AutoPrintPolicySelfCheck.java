package com.triathlon.prep.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Pure host doubles; LIVE-labelled doubles only exercise mode gates, never hardware. */
public final class AutoPrintPolicySelfCheck {
    static int passed;
    interface Test { void run() throws Exception; }
    static void check(boolean ok) { if (!ok) throw new AssertionError("Auto-print contract mismatch"); }
    static void test(String name, Test body) throws Exception { body.run();System.out.println("PASS "+(++passed)+" "+name); }
    static final class Store implements AutoPrintPolicy.Store {
        AutoPrintPolicy.Mode mode=AutoPrintPolicy.Mode.MANUAL;
        final Set<String> durable=new HashSet<>(); boolean saveFail,claimFail,loadThrow,claimThrowAfterWrite;
        int claims;
        public AutoPrintPolicy.Mode loadMode(){if(loadThrow)throw new IllegalStateException();return mode;}
        public boolean saveMode(AutoPrintPolicy.Mode requested){if(saveFail)return false;mode=requested;return true;}
        public synchronized AutoPrintPolicy.Claim claim(String key){claims++;if(claimFail)return AutoPrintPolicy.Claim.FAILED;if(!durable.add(key))return AutoPrintPolicy.Claim.ALREADY_CLAIMED;if(claimThrowAfterWrite)throw new IllegalStateException();return AutoPrintPolicy.Claim.CLAIMED;}
    }
    static final class Fixture {
        final Verification.Mode mode;
        Verification.Reply<Verification.Card> card;
        Verification.Reply<Verification.Capture> capture;
        Verification.Reply<Verification.Comparison> compare;
        boolean saveFail; long now=1; int sends; ReceiptPrintController.Reply printed;
        final Verification flow; final ReceiptPrintController printing;
        final CheckInReceipt.Layout layout=new CheckInReceipt.Layout(384,12,24);
        final Store policyStore;
        Fixture(Verification.Mode mode, Store policyStore) {
            this.mode=mode;this.policyStore=policyStore;
            RaceRoster roster=new RaceRoster("合成赛事",UUID.randomUUID().toString(),Arrays.asList(new RaceRoster.Entry("990101200001010014","001","合成选手","合成组","合成团体",RaceRoster.SwimTest.UNKNOWN)));
            Verification.Clock clock=new Verification.Clock(){public long elapsedMillis(){return now;}public long wallMillis(){return now;}};
            flow=new Verification(mode,new Verification.DeviceAdapter(){public Verification.Mode mode(){return mode;}public void readCard(String id,Verification.Reply<Verification.Card> r){card=r;}public void capture(String id,Verification.Reply<Verification.Capture> r){capture=r;}public void cancel(String id){}},
                new Verification.AlgorithmAdapter(){public Verification.Mode mode(){return mode;}public void compare(String id,byte[] chip,byte[] live,Verification.Reply<Verification.Comparison> r){compare=r;}public void cancel(String id){}},
                new Verification.RecordStore(){public void save(Verification.Record r)throws IOException{if(saveFail)throw new IOException("synthetic disk failure");}public List<Verification.Record> list(){return Collections.emptyList();}public void clear(){}},clock,100,()->roster);
            printing=new ReceiptPrintController(flow,new ReceiptPrintController.Transport(){public Verification.Mode mode(){return mode;}public void print(String id,List<String> lines,ReceiptPrintController.Reply r){
                check(policyStore.durable.contains(mode.name()+":"+flow.snapshot().sessionId));
                if(mode==Verification.Mode.TEST)check(String.join("",lines).contains("非有效凭条"));
                sends++;printed=r;
            }public void cancel(String id){}},clock,100);
        }
        void pass(){card.ok(new Verification.Card("合成证件", "990101200001010014",new byte[]{1}));capture.ok(new Verification.Capture(new byte[]{2},1,true));compare.ok(new Verification.Comparison(Verification.Liveness.PASSED,90d,80d,Verification.ScoreRule.AT_LEAST,"HOST_ONLY"));}
        AutoPrintPolicy.Attempt attempt(AutoPrintPolicy p){return p.tryAutomaticPrint(flow,printing,layout);}
    }
    static AutoPrintPolicy automatic(Store store){AutoPrintPolicy p=new AutoPrintPolicy(store);check(p.setMode(AutoPrintPolicy.Mode.AUTOMATIC));return p;}
    public static void main(String[] args) throws Exception {
        test("default manual does not reserve or send",()->{Store s=new Store();AutoPrintPolicy p=new AutoPrintPolicy(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();check(f.attempt(p).reason==AutoPrintPolicy.Reason.MANUAL_MODE&&f.sends==0&&s.claims==0);});
        test("mode persists but reconstruction is not fresh",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();AutoPrintPolicy restored=new AutoPrintPolicy(s);check(restored.mode()==AutoPrintPolicy.Mode.AUTOMATIC&&f.attempt(restored).reason==AutoPrintPolicy.Reason.NOT_FRESH&&f.sends==0);});
        test("current TEST pass saves reservation before simulated dispatch once",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();AutoPrintPolicy.Attempt a=f.attempt(p);check(a.reason==AutoPrintPolicy.Reason.DISPATCHED_UNCONFIRMED&&a.simulation&&a.jobId!=null&&f.sends==1);f.printed.accepted();check(f.printing.snapshot().status==ReceiptPrintController.Status.SIMULATED);check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH&&f.sends==1&&s.claims==1);});
        test("LIVE gate preserved with host-only mode doubles",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.LIVE,s);p.begin(f.flow);f.pass();check(!f.attempt(p).simulation&&f.sends==1);f.printed.accepted();check(f.printing.snapshot().status==ReceiptPrintController.Status.ACCEPTED);});
        test("save failure waits until explicit successful save retry",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);f.saveFail=true;p.begin(f.flow);f.pass();check(f.attempt(p).reason==AutoPrintPolicy.Reason.WAITING_FOR_SAVE&&f.sends==0&&s.claims==0);f.saveFail=false;f.flow.retrySave();f.attempt(p);check(f.sends==1);});
        test("not listed never dispatches",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.card.ok(new Verification.Card("名单外合成", "990101200001010022",new byte[]{1}));check(f.attempt(p).reason==AutoPrintPolicy.Reason.INELIGIBLE&&f.sends==0&&s.claims==0);});
        test("failed identity never dispatches",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.card.ok(new Verification.Card("合成", "990101200001010014",new byte[]{1}));f.capture.ok(new Verification.Capture(new byte[]{2},1,true));f.compare.ok(new Verification.Comparison(Verification.Liveness.FAILED,90d,80d,Verification.ScoreRule.AT_LEAST,"HOST_ONLY"));check(f.attempt(p).reason==AutoPrintPolicy.Reason.INELIGIBLE&&f.sends==0);});
        test("switch automatic does not chase manual completed result",()->{Store s=new Store();AutoPrintPolicy p=new AutoPrintPolicy(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();check(p.setMode(AutoPrintPolicy.Mode.AUTOMATIC));check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH&&f.sends==0);});
        test("mode switches invalidate even pending old session",()->{Store s=new Store();AutoPrintPolicy p=new AutoPrintPolicy(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);p.setMode(AutoPrintPolicy.Mode.AUTOMATIC);f.pass();check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH&&f.sends==0);});
        test("failed reservation never sends or automatically retries",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();s.claimFail=true;check(f.attempt(p).reason==AutoPrintPolicy.Reason.STORE_FAILED&&f.sends==0);s.claimFail=false;check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH&&s.claims==1);});
        test("crash-ambiguous persisted claim is never treated as printed or retried",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();s.claimThrowAfterWrite=true;check(f.attempt(p).reason==AutoPrintPolicy.Reason.STORE_FAILED&&f.sends==0&&s.durable.size()==1);AutoPrintPolicy restored=new AutoPrintPolicy(s);check(f.attempt(restored).reason==AutoPrintPolicy.Reason.NOT_FRESH);s.claimThrowAfterWrite=false;check(restored.beforeManualPrint(f.flow));});
        test("existing durable claim denies auto even for registered fresh session",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);String id=p.begin(f.flow);f.pass();s.durable.add("TEST:"+id);check(f.attempt(p).reason==AutoPrintPolicy.Reason.ALREADY_CLAIMED&&f.sends==0);});
        test("printer failure never automatic retry",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();f.attempt(p);f.printed.failed(ReceiptPrintController.Failure.PRINTER_ERROR);f.attempt(p);check(f.sends==1&&s.durable.size()==1);});
        test("printer timeout or cancel never automatic retry",()->{for(boolean timeout:new boolean[]{true,false}){Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();f.attempt(p);if(timeout){f.now=1000;f.printing.tick();}else f.printing.cancel();f.attempt(p);check(f.sends==1&&s.durable.size()==1);}});
        test("explicit manual retry allowed after auto failure",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();String first=f.attempt(p).jobId;f.printed.failed(ReceiptPrintController.Failure.PRINTER_ERROR);check(p.beforeManualPrint(f.flow));String second=f.printing.submit(CheckInReceipt.preparePreview(f.flow,f.flow.snapshot().sessionId),f.layout);check(!first.equals(second)&&f.sends==2);f.attempt(p);check(f.sends==2);});
        test("manual before observer suppresses auto",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();check(p.beforeManualPrint(f.flow));f.printing.submit(CheckInReceipt.preparePreview(f.flow,f.flow.snapshot().sessionId),f.layout);check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH&&f.sends==1);});
        test("manual reservation failure blocks explicit send permission",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();s.claimFail=true;check(!p.beforeManualPrint(f.flow)&&f.sends==0);s.claimFail=false;check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH);});
        test("next and background invalidate without deleting ledger",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();p.invalidate();check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH&&f.sends==0);p.begin(f.flow);f.pass();f.attempt(p);f.flow.next();check(!p.beforeManualPrint(f.flow)&&s.durable.size()==1);});
        test("other owner or changed session cannot dispatch",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s),other=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();other.flow.begin();other.pass();check(other.attempt(p).reason==AutoPrintPolicy.Reason.STALE_SESSION);f.flow.begin();f.pass();check(f.attempt(p).reason==AutoPrintPolicy.Reason.STALE_SESSION&&f.sends==0);});
        test("failed mode save retains active mode but revokes pending automatic grant",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();s.saveFail=true;check(!p.setMode(AutoPrintPolicy.Mode.MANUAL)&&p.mode()==AutoPrintPolicy.Mode.AUTOMATIC&&f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH);});
        test("corrupt load defaults manual",()->{Store s=new Store();s.loadThrow=true;check(new AutoPrintPolicy(s).mode()==AutoPrintPolicy.Mode.MANUAL);s.loadThrow=false;s.mode=null;check(new AutoPrintPolicy(s).mode()==AutoPrintPolicy.Mode.MANUAL);});
        test("concurrent observers dispatch once",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();CountDownLatch go=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();List<Thread> threads=new ArrayList<>();for(int i=0;i<16;i++){Thread t=new Thread(()->{try{go.await();f.attempt(p);}catch(Throwable ex){error.set(ex);}});threads.add(t);t.start();}go.countDown();for(Thread t:threads)t.join(3000);check(error.get()==null&&f.sends==1&&s.claims==1);});
        test("wrong TEST LIVE printer mode still denied after durable reservation",()->{Store s=new Store();AutoPrintPolicy p=automatic(s);Fixture f=new Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();ReceiptPrintController bad=new ReceiptPrintController(f.flow,new ReceiptPrintController.Transport(){public Verification.Mode mode(){return Verification.Mode.LIVE;}public void print(String id,List<String> lines,ReceiptPrintController.Reply reply){throw new AssertionError("mode crossed");}public void cancel(String id){}},new Verification.Clock(){public long elapsedMillis(){return 1;}public long wallMillis(){return 1;}},100);check(p.tryAutomaticPrint(f.flow,bad,f.layout).reason==AutoPrintPolicy.Reason.SUBMIT_FAILED_UNCONFIRMED&&s.durable.size()==1&&f.sends==0);check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH);});
        System.out.println("PASS_TOTAL="+passed+"; HARDWARE_EXECUTED=false");
    }
}
