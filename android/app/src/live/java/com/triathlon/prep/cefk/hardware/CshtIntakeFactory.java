package com.triathlon.prep.cefk.hardware;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.intake.AutoCardIntake;
import com.triathlon.prep.intake.IntakeCard;
import java.util.Arrays;

/** Existing real SDK factory seam. Caller keeps the existing LIVE configuration/authorization gate. */
public final class CshtIntakeFactory implements AutoCardIntake.SourceFactory {
    private final CefkCardReader.Factory factory;
    public CshtIntakeFactory(CefkCardReader.Factory factory) {
        if (factory == null) throw new IllegalArgumentException("Configured SDK factory required");
        this.factory = factory;
    }
    @Override public AutoCardIntake.Source create() throws Exception {
        CefkCardReader.Backend backend = factory.create();
        if (backend == null) throw new IllegalStateException("No reader backend");
        return new AutoCardIntake.Source() {
            @Override public Verification.Mode mode() { return Verification.Mode.LIVE; }
            @Override public void initialize(AutoCardIntake.Callback callback) throws Exception { backend.initialize(events(callback)); }
            @Override public void read(AutoCardIntake.Callback callback) throws Exception { backend.read(events(callback)); }
            @Override public void close() throws Exception { backend.close(); }
            private CefkCardReader.Events events(AutoCardIntake.Callback callback) {
                return new CefkCardReader.Events() {
                    @Override public void initialized() { callback.initialized(); }
                    @Override public void card(CefkCardReader.Card card) {
                        byte[] png = null;
                        try {
                            png = card.photoPng();
                            callback.card(new IntakeCard(Verification.Mode.LIVE, card.name(), card.number(), png));
                        } catch (Exception | LinkageError ex) { callback.failed(AutoCardIntake.Error.SOURCE_FAILED); }
                        finally { if (card != null) card.close(); if (png != null) Arrays.fill(png, (byte) 0); }
                    }
                    @Override public void failed(CefkCardReader.Error error) {
                        callback.failed(error == CefkCardReader.Error.INIT_FAILED
                                ? AutoCardIntake.Error.INIT_FAILED : AutoCardIntake.Error.SOURCE_FAILED);
                    }
                };
            }
        };
    }
}
