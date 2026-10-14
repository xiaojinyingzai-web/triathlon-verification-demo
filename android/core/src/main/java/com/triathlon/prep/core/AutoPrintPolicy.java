package com.triathlon.prep.core;

/** Automatic dispatch permission, not a paper-output record. Own one policy per runtime. */
public final class AutoPrintPolicy {
    public enum Mode { MANUAL, AUTOMATIC }
    public enum Claim { CLAIMED, ALREADY_CLAIMED, FAILED }
    public interface Store {
        Mode loadMode() throws Exception;
        boolean saveMode(Mode mode) throws Exception;
        /** Missing key in a legacy store preserves printing. */
        default boolean loadEnabled() throws Exception { return true; }
        default boolean saveEnabled(boolean enabled) throws Exception { throw new UnsupportedOperationException("Printing switch storage required"); }
        /** Atomic durable test-and-set. Never clear reservations on printer failure or restart. */
        Claim claim(String receiptKey) throws Exception;
    }
    public enum Reason { PRINT_DISABLED, MANUAL_MODE, NOT_FRESH, STALE_SESSION, INELIGIBLE, WAITING_FOR_SAVE,
        ALREADY_CLAIMED, STORE_FAILED, DISPATCHED_UNCONFIRMED, SUBMIT_FAILED_UNCONFIRMED, DUPLICATE_CHECKIN, HISTORY_UNLINKABLE }
    public static final class Attempt {
        public final Reason reason;
        public final String jobId;
        public final boolean simulation;
        private Attempt(Reason reason, String jobId, boolean simulation) {
            this.reason = reason; this.jobId = jobId; this.simulation = simulation;
        }
    }
    private final Store store;
    private Mode mode;
    private boolean enabled=true,settingsFailed;
    private volatile boolean printingInhibited=true;
    private Verification owner;
    private String sessionId;
    private Verification.Mode verificationMode;
    private boolean fresh;

    public AutoPrintPolicy(Store store) {
        if (store == null) throw new IllegalArgumentException("Durable store required");
        this.store = store;
        try { mode = store.loadMode(); }
        catch (Exception | LinkageError ignored) { mode = Mode.MANUAL; settingsFailed=true; }
        if (mode == null) mode = Mode.MANUAL;
        try { enabled=store.loadEnabled(); }
        catch(Exception|LinkageError ignored) { enabled=false;settingsFailed=true; }
        printingInhibited=settingsFailed||!enabled;
        // Loading persisted AUTOMATIC mode does not grant a recovered result permission to print.
    }
    public synchronized Mode mode() { return mode; }
    public synchronized boolean enabled() { return enabled; }
    public synchronized boolean settingsFailed() { return settingsFailed; }
    /** Nonblocking admission gate: the UI can inhibit while an earlier send is in progress. */
    public boolean printingAllowed() { return !printingInhibited; }
    public void inhibitPrinting() { printingInhibited=true; }
    public synchronized boolean setEnabled(boolean requested) {
        inhibitPrinting();invalidate();
        try {
            Mode restored=requested?store.loadMode():mode;
            if(restored==null)throw new IllegalStateException("Print mode unconfirmed");
            if(!store.saveEnabled(requested)){settingsFailed=true;return false;}
            mode=restored;
        }catch(Exception|LinkageError ignored){settingsFailed=true;return false;}
        enabled=requested;settingsFailed=false;printingInhibited=!requested;
        return true;
    }
    /** Call off the UI thread. Failed/uncertain saves revoke automatic permission conservatively. */
    public synchronized boolean setMode(Mode requested) {
        if (requested == null) throw new IllegalArgumentException("Mode required");
        try {
            if (!store.saveMode(requested)) { fresh = false; settingsFailed=true;inhibitPrinting();return false; }
        } catch (Exception | LinkageError ignored) { fresh = false;settingsFailed=true;inhibitPrinting();return false; }
        if (mode != requested) invalidate();
        mode = requested;
        return true;
    }
    /** The only fresh-session entry. Do not use this when restoring an Activity or historical result. */
    public synchronized String begin(Verification flow) {
        if (flow == null) throw new IllegalArgumentException("Verification required");
        invalidate();
        String id = flow.begin();
        Verification.Snapshot snapshot = flow.snapshot();
        if (id != null && !id.isEmpty() && id.equals(snapshot.sessionId)) {
            owner = flow; sessionId = id; verificationMode = snapshot.mode; fresh = true;
        }
        return id;
    }
    /** Off UI thread: persists reservation BEFORE submit. Never an automatic retry entry. */
    public synchronized Attempt tryAutomaticPrint(Verification flow, ReceiptPrintController printing,
            CheckInReceipt.Layout layout) {
        if(!printingAllowed()){fresh=false;return attempt(Reason.PRINT_DISABLED,null,false);}
        if (mode != Mode.AUTOMATIC) return attempt(Reason.MANUAL_MODE, null, false);
        if (!fresh) return attempt(Reason.NOT_FRESH, null, false);
        if (flow == null || flow != owner) return attempt(Reason.STALE_SESSION, null, false);
        Verification.Snapshot snapshot = flow.snapshot();
        if (!sessionId.equals(snapshot.sessionId) || snapshot.mode != verificationMode) {
            invalidate(); return attempt(Reason.STALE_SESSION, null, false);
        }
        boolean simulation = snapshot.mode == Verification.Mode.TEST;
        if (snapshot.stage != Verification.Stage.FINISHED)
            return attempt(Reason.INELIGIBLE, null, simulation);
        if (snapshot.saveState != Verification.SaveState.SAVED)
            return attempt(Reason.WAITING_FOR_SAVE, null, simulation);
        if(snapshot.checkIn==CheckIn.State.DUPLICATE){fresh=false;return attempt(Reason.DUPLICATE_CHECKIN,null,simulation);}
        if(snapshot.checkIn==CheckIn.State.UNLINKABLE){fresh=false;return attempt(Reason.HISTORY_UNLINKABLE,null,simulation);}
        CheckInReceipt.Document document = currentDocument(flow, snapshot);
        if (document == null) { fresh = false; return attempt(Reason.INELIGIBLE, null, simulation); }
        if (printing == null || layout == null) {
            fresh = false; return attempt(Reason.INELIGIBLE, null, simulation);
        }
        // Consume the in-memory event as well: even a failed store cannot be polled into a retry.
        fresh = false;
        Claim claim = reserve(snapshot);
        if (claim == Claim.ALREADY_CLAIMED) return attempt(Reason.ALREADY_CLAIMED, null, simulation);
        if (claim != Claim.CLAIMED) return attempt(Reason.STORE_FAILED, null, simulation);
        try {
            if(!printingAllowed())return attempt(Reason.PRINT_DISABLED,null,simulation);
            // Controller rechecks current document and TEST/LIVE transport mode after the disk write.
            String jobId = printing.submit(document, layout);
            return attempt(Reason.DISPATCHED_UNCONFIRMED, jobId, simulation);
        } catch (RuntimeException | LinkageError ignored) {
            // Could have reached a transport before throwing. Keep the durable reservation forever.
            return attempt(Reason.SUBMIT_FAILED_UNCONFIRMED, null, simulation);
        }
    }
    /** Explicit manual print/reprint remains allowed if previously reserved, but must still be eligible.
     * Caller then invokes existing controller.submit; no transport is called by this method.
     */
    public synchronized boolean beforeManualPrint(Verification flow) {
        if(!printingAllowed())return false;
        if (flow == null) return false;
        Verification.Snapshot snapshot = flow.snapshot();
        if (currentDocument(flow, snapshot) == null) return false;
        if (owner == flow && sessionId != null && sessionId.equals(snapshot.sessionId)) fresh = false;
        Claim claim = reserve(snapshot);
        return claim == Claim.CLAIMED || claim == Claim.ALREADY_CLAIMED;
    }
    /** Explicit human channel only; never participates in automatic fresh-session dispatch. */
    public synchronized boolean beforeManualPassportPrint(ManualPrintAuthorization authority){
        if(!printingAllowed()||authority==null||!authority.current())return false;
        try{Claim claim=store.claim("MANUAL_PASSPORT|"+authority.record.key()+"|"+authority.record.id);return claim==Claim.CLAIMED||authority.reprint&&claim==Claim.ALREADY_CLAIMED;}catch(Exception|LinkageError error){return false;}
    }
    private Claim reserve(Verification.Snapshot snapshot) {
        try {
            Claim claim = store.claim(snapshot.mode.name() + ":" + snapshot.sessionId);
            return claim == null ? Claim.FAILED : claim;
        } catch (Exception | LinkageError ignored) { return Claim.FAILED; }
    }
    private static CheckInReceipt.Document currentDocument(Verification flow, Verification.Snapshot snapshot) {
        return snapshot.mode == Verification.Mode.TEST
                ? CheckInReceipt.preparePreview(flow, snapshot.sessionId)
                : CheckInReceipt.prepare(flow, snapshot.sessionId);
    }
    private static Attempt attempt(Reason reason, String jobId, boolean simulation) {
        return new Attempt(reason, jobId, simulation);
    }
    /** Cancel/next/background; reconstruction never reinstates fresh permission. */
    public synchronized void invalidate() {
        owner = null; sessionId = null; verificationMode = null; fresh = false;
    }
}
