package com.triathlon.prep.intake;

import android.content.Context;
import android.content.SharedPreferences;
import com.triathlon.prep.core.IdentityNumber;
import com.triathlon.prep.core.Verification;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Local keyed token only. Single-process check+commit; no card number/photo is persisted. */
public final class PreferencesCardFence implements CardAdmissionFence {
    public static final String PREFERENCES_NAME = "triathlon_card_admission_v1";
    private static final Object PROCESS_LOCK = new Object();
    private static final String SECRET = "local_hmac_secret";
    private final SharedPreferences preferences;
    public PreferencesCardFence(Context context) {
        if (context == null) throw new IllegalArgumentException("Context required");
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
    PreferencesCardFence(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("Preferences required");
        this.preferences = preferences;
    }
    @Override public Result admit(Verification.Mode mode, String number) {
        if (mode == null) return Result.FAILED;
        synchronized (PROCESS_LOCK) {
            byte[] secret = null;
            try {
                String normalized = IdentityNumber.normalize(number);
                String savedSecret = preferences.getString(SECRET, null);
                if (savedSecret == null) {
                    // Missing key with a retained fence must not silently forget the previous card.
                    if (preferences.contains("last_TEST") || preferences.contains("last_LIVE")) return Result.FAILED;
                    secret = new byte[32]; new SecureRandom().nextBytes(secret);
                } else {
                    if (!savedSecret.matches("[0-9a-f]{64}")) return Result.FAILED;
                    secret = decode(savedSecret);
                }
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                String token = hex(mac.doFinal((mode.name() + ":" + normalized).getBytes(StandardCharsets.UTF_8)));
                String key = "last_" + mode.name();
                if (preferences.contains(key)) {
                    String last = preferences.getString(key, null);
                    if (last == null || !last.matches("[0-9a-f]{64}")) return Result.FAILED;
                    if (token.equals(last)) return Result.SAME_CARD;
                }
                // Write the key with the token in the SAME synchronous commit, including first use.
                return preferences.edit().putString(SECRET, hex(secret)).putString(key, token).commit()
                        ? Result.ADMITTED : Result.FAILED;
            } catch (Exception | LinkageError ignored) { return Result.FAILED; }
            finally { if (secret != null) Arrays.fill(secret, (byte) 0); }
        }
    }
    @Override public boolean explicitReverify(Verification.Mode mode) {
        if (mode == null) return false;
        synchronized (PROCESS_LOCK) {
            String key = "last_" + mode.name(); String previous;
            try { previous = preferences.getString(key, null); }
            catch (RuntimeException ignored) { return false; }
            try { if (preferences.edit().remove(key).commit()) return true; }
            catch (RuntimeException ignored) { /* Keep the fence, including the shared memory cache. */ }
            if (previous != null) {
                try { preferences.edit().putString(key, previous).commit(); }
                catch (RuntimeException ignored) { /* Caller remains stopped on fence failure. */ }
            }
            return false;
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
    private static byte[] decode(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
