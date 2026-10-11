package com.triathlon.prep.intake;

import com.triathlon.prep.core.Verification;

/** One admitted card, one core read. Does not open/read any hardware. Runtime owns capture delegate. */
public final class CardInbox implements Verification.DeviceAdapter, AutoCloseable {
    private final Verification.Mode mode;
    private final Verification.DeviceAdapter captureDelegate;
    private IntakeCard pending;
    private AutoCardIntake.Admission pendingAdmission;
    private String currentSession;
    private boolean closed;
    public CardInbox(Verification.Mode mode, Verification.DeviceAdapter captureDelegate) {
        if (mode == null || (captureDelegate != null && captureDelegate.mode() != mode))
            throw new IllegalArgumentException("Matching intake/capture mode required");
        this.mode = mode; this.captureDelegate = captureDelegate;
    }
    @Override public Verification.Mode mode() { return mode; }
    /** On success this inbox owns the Admission's card; invalid/expired admission cannot be reused. */
    public synchronized boolean offer(AutoCardIntake.Admission admission) {
        if (closed || pending != null || admission == null || admission.mode != mode) return false;
        IntakeCard card = admission.takeCard();
        if (card == null) return false;
        pending = card; pendingAdmission = admission; currentSession = null; return true;
    }
    @Override public void readCard(String id, Verification.Reply<Verification.Card> reply) {
        if (reply == null) throw new IllegalArgumentException("Reply required");
        Verification.Card result = null;
        synchronized (this) {
            if (!closed && pending != null && id != null && !id.trim().isEmpty()) {
                try {
                    if (pendingAdmission.bindCoreSession(id)) { result = pending.copyForCore(); currentSession = id; }
                } finally { pending.close(); pending = null; pendingAdmission = null; }
            }
        }
        if (result == null) reply.fail(Verification.Error.NOT_CONNECTED);
        else {
            try { reply.ok(result); }
            catch (RuntimeException ex) { result.close(); throw ex; }
        }
    }
    @Override public void capture(String id, Verification.Reply<Verification.Capture> reply) {
        if (reply == null) throw new IllegalArgumentException("Reply required");
        boolean unavailable;
        synchronized (this) { unavailable = closed || currentSession == null || !currentSession.equals(id) || captureDelegate == null; }
        if (unavailable) { reply.fail(Verification.Error.NOT_CONNECTED); return; }
        captureDelegate.capture(id, reply);
    }
    @Override public void cancel(String id) {
        synchronized (this) {
            if (currentSession != null && !currentSession.equals(id)) return;
            clear();
        }
        if (captureDelegate != null) captureDelegate.cancel(id);
    }
    public synchronized void clear() {
        if (pending != null) pending.close(); pending = null; pendingAdmission = null; currentSession = null;
    }
    @Override public synchronized void close() { closed = true; clear(); }
}
