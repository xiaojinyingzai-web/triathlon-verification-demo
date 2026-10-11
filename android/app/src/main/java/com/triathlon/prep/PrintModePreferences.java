package com.triathlon.prep;

import android.content.Context;
import android.content.SharedPreferences;
import com.triathlon.prep.core.AutoPrintPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** One-process synchronous durable store. No identity data; no "printed" assertions. */
public final class PrintModePreferences implements AutoPrintPolicy.Store {
    public static final String PREFERENCES_NAME = "triathlon_print_policy_v1";
    private static final String MODE = "mode";
    private static final String RESERVED = "RESERVED_OR_UNCONFIRMED";
    // SharedPreferences has no CAS; serialize check+commit across all our store instances.
    private static final Object PROCESS_LOCK = new Object();
    private final SharedPreferences preferences;
    public PrintModePreferences(Context context) {
        if (context == null) throw new IllegalArgumentException("Context required");
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
    /** Package-private injectable seam for host storage-fault tests; no production fake backend. */
    PrintModePreferences(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("Preferences required");
        this.preferences = preferences;
    }
    @Override public AutoPrintPolicy.Mode loadMode() {
        synchronized (PROCESS_LOCK) {
            try {
                String value = preferences.getString(MODE, AutoPrintPolicy.Mode.MANUAL.name());
                return AutoPrintPolicy.Mode.AUTOMATIC.name().equals(value)
                        ? AutoPrintPolicy.Mode.AUTOMATIC : AutoPrintPolicy.Mode.MANUAL;
            } catch (RuntimeException ignored) { return AutoPrintPolicy.Mode.MANUAL; }
        }
    }
    @Override public boolean saveMode(AutoPrintPolicy.Mode mode) {
        if (mode == null) throw new IllegalArgumentException("Mode required");
        synchronized (PROCESS_LOCK) {
            String previous = loadMode().name();
            try {
                if (preferences.edit().putString(MODE, mode.name()).commit()) return true;
            } catch (RuntimeException ignored) { /* commit may already have changed the memory cache */ }
            // SharedPreferences changes its memory cache even if disk commit fails. Restore the
            // previous effective value so another in-process owner cannot read a failed enable.
            try { preferences.edit().putString(MODE, previous).commit(); }
            catch (RuntimeException ignored) { /* policy retains old mode and revokes fresh permission */ }
            return false;
        }
    }
    @Override public AutoPrintPolicy.Claim claim(String receiptKey) {
        if (receiptKey == null || !receiptKey.matches("(TEST|LIVE):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            return AutoPrintPolicy.Claim.FAILED;
        synchronized (PROCESS_LOCK) {
            try {
                String key = "reserved_" + digest(receiptKey);
                // Even an unknown/corrupt existing value is not permission to resubmit.
                if (preferences.contains(key)) return AutoPrintPolicy.Claim.ALREADY_CLAIMED;
                boolean saved = preferences.edit().putString(key, RESERVED).commit();
                return saved ? AutoPrintPolicy.Claim.CLAIMED : AutoPrintPolicy.Claim.FAILED;
            } catch (Exception | LinkageError ignored) { return AutoPrintPolicy.Claim.FAILED; }
        }
    }
    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }
}
