package com.triathlon.prep.core;

import java.lang.reflect.*;
import java.util.function.BooleanSupplier;

/** Real policy/controller; only durable storage and physical send are controlled seams. */
public final class PrintSwitchSelfCheck {
    static int passed;
    static void check(boolean ok) { if (!ok) throw new AssertionError("Print master switch behavior"); }
    static Object call(Object owner,String method,Class<?> type,Object arg) throws Exception {
        try {
            Method m=type==null?owner.getClass().getMethod(method):owner.getClass().getMethod(method,type);
            return type==null?m.invoke(owner):m.invoke(owner,arg);
        } catch(NoSuchMethodException ex) { throw new AssertionError("Missing print master switch: "+method,ex); }
        catch(InvocationTargetException ex) { throw new AssertionError(method+" failed",ex.getCause()); }
    }
    static boolean enabled(AutoPrintPolicy p)throws Exception{return (Boolean)call(p,"enabled",null,null);}
    static boolean allowed(AutoPrintPolicy p)throws Exception{return (Boolean)call(p,"printingAllowed",null,null);}
    static boolean toggle(AutoPrintPolicy p,boolean value)throws Exception{return (Boolean)call(p,"setEnabled",boolean.class,value);}
    static void test(String name,AutoPrintPolicySelfCheck.Test body)throws Exception{body.run();System.out.println("PASS "+(++passed)+" "+name);}
    static final class Store implements AutoPrintPolicy.Store {
        final AutoPrintPolicySelfCheck.Store base=new AutoPrintPolicySelfCheck.Store();
        boolean on=true,readFail,writeFail;
        public AutoPrintPolicy.Mode loadMode(){return base.loadMode();}
        public boolean saveMode(AutoPrintPolicy.Mode m){return base.saveMode(m);}
        public AutoPrintPolicy.Claim claim(String key){return base.claim(key);}
        public boolean loadEnabled(){if(readFail)throw new IllegalStateException("synthetic read fault");return on;}
        public boolean saveEnabled(boolean requested){if(writeFail)return false;on=requested;return true;}
    }
    static AutoPrintPolicy automatic(Store s){AutoPrintPolicy p=new AutoPrintPolicy(s);check(p.setMode(AutoPrintPolicy.Mode.AUTOMATIC));return p;}
    static AutoPrintPolicySelfCheck.Fixture fixture(Store s){return new AutoPrintPolicySelfCheck.Fixture(Verification.Mode.TEST,s.base);}
    static void gate(AutoPrintPolicySelfCheck.Fixture f,AutoPrintPolicy p)throws Exception{
        call(f.printing,"setPrintPermission",BooleanSupplier.class,(BooleanSupplier)()->{try{return allowed(p);}catch(Exception ex){throw new AssertionError(ex);}});
    }
    public static void main(String[] args)throws Exception{
        test("legacy store defaults ON and manual remains allowed",()->{
            AutoPrintPolicySelfCheck.Store s=new AutoPrintPolicySelfCheck.Store();AutoPrintPolicy p=new AutoPrintPolicy(s);
            AutoPrintPolicySelfCheck.Fixture f=new AutoPrintPolicySelfCheck.Fixture(Verification.Mode.TEST,s);p.begin(f.flow);f.pass();
            check(enabled(p)&&allowed(p)&&p.beforeManualPrint(f.flow));
        });
        test("OFF blocks manual automatic and preserves saved verification and mode",()->{
            Store s=new Store();AutoPrintPolicy p=automatic(s);AutoPrintPolicySelfCheck.Fixture f=fixture(s);gate(f,p);
            p.begin(f.flow);check(toggle(p,false));f.pass();
            check(!enabled(p)&&!allowed(p)&&p.mode()==AutoPrintPolicy.Mode.AUTOMATIC);
            check(f.flow.snapshot().saveState==Verification.SaveState.SAVED&&!p.beforeManualPrint(f.flow));
            check(f.attempt(p).reason.name().equals("PRINT_DISABLED")&&f.sends==0&&s.base.claims==0);
        });
        test("re-enable never backfills disabled session but explicit manual can print",()->{
            Store s=new Store();AutoPrintPolicy p=automatic(s);AutoPrintPolicySelfCheck.Fixture f=fixture(s);gate(f,p);
            check(toggle(p,false));p.begin(f.flow);f.pass();check(toggle(p,true));
            check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH&&f.sends==0);
            check(p.beforeManualPrint(f.flow));f.printing.submit(CheckInReceipt.preparePreview(f.flow,f.flow.snapshot().sessionId),f.layout);check(f.sends==1);
        });
        test("OFF survives reconstruction and next fresh ON session prints once",()->{
            Store s=new Store();AutoPrintPolicy p=automatic(s);check(toggle(p,false));AutoPrintPolicy restored=new AutoPrintPolicy(s);
            check(!enabled(restored)&&!allowed(restored)&&restored.mode()==AutoPrintPolicy.Mode.AUTOMATIC);
            check(toggle(restored,true));AutoPrintPolicySelfCheck.Fixture f=fixture(s);gate(f,restored);restored.begin(f.flow);f.pass();
            f.attempt(restored);f.printed.accepted();f.attempt(restored);check(f.sends==1);
        });
        test("read and write faults fail closed without claiming saved setting",()->{
            Store s=new Store();s.readFail=true;AutoPrintPolicy p=new AutoPrintPolicy(s);check(!allowed(p));
            s.readFail=false;check(toggle(p,true)&&allowed(p));s.writeFail=true;
            check(!toggle(p,false)&&enabled(p)&&!allowed(p));check(!toggle(p,true)&&!allowed(p));
            s.writeFail=false;check(toggle(p,true)&&allowed(p));
        });
        test("controller refuses new send and retry after OFF while accepted job remains",()->{
            Store s=new Store();AutoPrintPolicy p=automatic(s);AutoPrintPolicySelfCheck.Fixture f=fixture(s);gate(f,p);p.begin(f.flow);f.pass();
            f.attempt(p);check(toggle(p,false));check(f.printing.snapshot().status==ReceiptPrintController.Status.SENDING);
            f.printed.accepted();String id=f.flow.snapshot().sessionId;check(!f.printing.authorizeRetry(id,true));
            try{f.printing.submit(CheckInReceipt.preparePreview(f.flow,id),f.layout);throw new AssertionError("OFF reached transport");}catch(IllegalStateException expected){}
            check(f.sends==1&&s.base.durable.size()==1);check(toggle(p,true));check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH);
            check(f.printing.authorizeRetry(id,true)&&p.beforeManualPrint(f.flow));f.printing.submit(CheckInReceipt.preparePreview(f.flow,id),f.layout);check(f.sends==2&&s.base.durable.size()==1);
        });
        test("failed mode write also blocks new print until explicit setting recovery",()->{
            Store s=new Store();AutoPrintPolicy p=automatic(s);s.base.saveFail=true;check(!p.setMode(AutoPrintPolicy.Mode.MANUAL)&&!allowed(p));
            s.base.saveFail=false;check(toggle(p,true)&&allowed(p)&&p.mode()==AutoPrintPolicy.Mode.AUTOMATIC);
        });
        test("inhibit immediately closes admission before queued persistence",()->{
            Store s=new Store();AutoPrintPolicy p=automatic(s);AutoPrintPolicySelfCheck.Fixture f=fixture(s);gate(f,p);p.begin(f.flow);f.pass();
            call(p,"inhibitPrinting",null,null);check(enabled(p)&&!allowed(p)&&!p.beforeManualPrint(f.flow));
            check(toggle(p,true));check(f.attempt(p).reason==AutoPrintPolicy.Reason.NOT_FRESH&&f.sends==0);
        });
        System.out.println("PASS_TOTAL="+passed);
    }
}
