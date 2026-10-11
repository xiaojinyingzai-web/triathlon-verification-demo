package com.triathlon.prep;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.core.ReceiptPrintController;
import com.triathlon.prep.core.CheckInReceipt;
import com.triathlon.prep.intake.CardInbox;
import com.triathlon.prep.intake.AutoCardIntake;

/** One owned core and its adapters across pages/configuration recreation. */
public final class VerificationRuntime implements AutoCloseable {
    public final Verification flow;
    public final VerificationDisplay display;
    public final ReceiptPrintController printing;
    public final CheckInReceipt.Layout printLayout;
    public final CardInbox inbox;
    public final AutoCardIntake.SourceFactory cardSource;
    private volatile String receiptHeading="";
    private final boolean beginEnabled;
    private final Runnable release;
    private boolean closed;
    VerificationRuntime(Verification flow,boolean beginEnabled,Runnable release) {
        this(flow,beginEnabled,release,null);
    }
    VerificationRuntime(Verification flow,boolean beginEnabled,Runnable release,VerificationDisplay display) {
        this(flow,beginEnabled,release,display,null,null,15000);
    }
    VerificationRuntime(Verification flow,boolean beginEnabled,Runnable release,VerificationDisplay display,
            ReceiptPrintController.Transport printer,Verification.Clock clock,long printTimeoutMillis) {
        this(flow,beginEnabled,release,display,printer,clock,printTimeoutMillis,flow.snapshot().mode==Verification.Mode.TEST?new CheckInReceipt.Layout(384,12,24):null);
    }
    VerificationRuntime(Verification flow,boolean beginEnabled,Runnable release,VerificationDisplay display,
            ReceiptPrintController.Transport printer,Verification.Clock clock,long printTimeoutMillis,CheckInReceipt.Layout printLayout) {
        this(flow,beginEnabled,release,display,printer,clock,printTimeoutMillis,printLayout,null,null);
    }
    VerificationRuntime(Verification flow,boolean beginEnabled,Runnable release,VerificationDisplay display,
            ReceiptPrintController.Transport printer,Verification.Clock clock,long printTimeoutMillis,CheckInReceipt.Layout printLayout,
            CardInbox inbox,AutoCardIntake.SourceFactory cardSource) {
        if(flow==null || release==null)throw new IllegalArgumentException("Owned runtime required");
        if((printer==null)!=(clock==null))throw new IllegalArgumentException("Explicit printer and clock must be supplied together");
        this.flow=flow;this.beginEnabled=beginEnabled;this.release=release;this.display=display;
        this.printLayout=printLayout;
        if((inbox==null)!=(cardSource==null))throw new IllegalArgumentException("Inbox and source required together");
        this.inbox=inbox;this.cardSource=cardSource;
        ReceiptPrintController.Transport selected=printer==null?new ReceiptPrintController.Transport(){
            public Verification.Mode mode(){return flow.snapshot().mode;}
            public void print(String id,java.util.List<String> lines,ReceiptPrintController.Reply reply){reply.failed(ReceiptPrintController.Failure.NOT_CONNECTED);}
            public void cancel(String id){}
        }:printer;
        Verification.Clock selectedClock=clock==null?new Verification.Clock(){
            public long elapsedMillis(){return System.nanoTime()/1000000L;}
            public long wallMillis(){return System.currentTimeMillis();}
        }:clock;
        printing=new ReceiptPrintController(flow,selected,selectedClock,printTimeoutMillis,()->receiptHeading);
    }
    public String receiptHeading(){return receiptHeading;}
    public void setReceiptHeading(String value){
        String normalized=CheckInReceipt.normalizeHeading(value);
        synchronized(printing){if(printing.snapshot().status==ReceiptPrintController.Status.SENDING)throw new IllegalStateException("Printing is in flight");receiptHeading=normalized;}
    }
    public synchronized boolean canBegin(){return !closed && beginEnabled;}
    @Override public void close() {
        synchronized(this){if(closed)return;closed=true;}
        try{printing.close();}finally{try{flow.cancel();}finally{try{if(inbox!=null)inbox.close();release.run();}finally{if(display!=null)display.close();}}}
    }
}
