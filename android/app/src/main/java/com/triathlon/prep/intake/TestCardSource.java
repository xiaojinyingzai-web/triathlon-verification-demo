package com.triathlon.prep.intake;

import com.triathlon.prep.core.Verification;

/** Explicit synthetic TEST input. Never opens a device, camera, or network connection. */
public final class TestCardSource implements AutoCardIntake.SourceFactory {
    private Session current;
    @Override public synchronized AutoCardIntake.Source create() { current = new Session(); return current; }
    /** Caller supplies fictional fields/image. Returns false if TEST intake is not reading. */
    public boolean emit(String name, String fictionalNumber, byte[] syntheticChipPhoto) {
        Session session;
        synchronized (this) { session = current; }
        IntakeCard card = new IntakeCard(Verification.Mode.TEST, name, fictionalNumber, syntheticChipPhoto);
        AutoCardIntake.Callback callback;
        synchronized (this) {
            if (session == null || session.closed || session.callback == null) { card.close(); return false; }
            callback = session.callback;
        }
        callback.card(card); return true;
    }
    private final class Session implements AutoCardIntake.Source {
        AutoCardIntake.Callback callback; boolean closed;
        @Override public Verification.Mode mode() { return Verification.Mode.TEST; }
        @Override public void initialize(AutoCardIntake.Callback callback) { callback.initialized(); }
        @Override public void read(AutoCardIntake.Callback callback) { synchronized (TestCardSource.this) { if (!closed) this.callback = callback; } }
        @Override public void close() { synchronized (TestCardSource.this) { closed = true; callback = null; } }
    }
}
