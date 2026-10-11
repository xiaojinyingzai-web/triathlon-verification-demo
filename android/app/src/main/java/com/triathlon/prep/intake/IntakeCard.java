package com.triathlon.prep.intake;

import com.triathlon.prep.core.IdentityNumber;
import com.triathlon.prep.core.Verification;
import java.util.Arrays;

/** Owned chip-photo copy; no diagnostic string includes identity fields. */
public final class IntakeCard implements AutoCloseable {
    public final Verification.Mode mode;
    private String name, number;
    private byte[] photo;
    public IntakeCard(Verification.Mode mode, String name, String number, byte[] photo) {
        if (mode == null || name == null || name.trim().isEmpty() || name.length() > 128
                || photo == null || photo.length == 0 || photo.length > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid intake card");
        this.mode = mode; this.name = name; this.number = IdentityNumber.normalize(number);
        this.photo = photo.clone();
    }
    public synchronized String number() { requireOpen(); return number; }
    public synchronized Verification.Card copyForCore() {
        requireOpen(); return new Verification.Card(name, number, photo);
    }
    private void requireOpen() { if (photo == null) throw new IllegalStateException("Intake card closed"); }
    @Override public synchronized void close() {
        if (photo != null) Arrays.fill(photo, (byte) 0);
        photo = null; name = null; number = null;
    }
}
