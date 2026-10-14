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
    private static final String ENABLED="enabled";
    private static final java.util.Set<SharedPreferences> FAILED=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<SharedPreferences,Boolean>());
    private static final String RESERVED = "RESERVED_OR_UNCONFIRMED";
    // SharedPreferences has no CAS; serialize check+commit across all our store instances.
    private static final Object PROCESS_LOCK = new Object();
    private final SharedPreferences preferences;
    public interface ClaimBackend {AutoPrintPolicy.Claim reserve(String digest)throws Exception;}
    private final ClaimBackend claims;
    public PrintModePreferences(Context context) {
        this(context,null);
    }
    public PrintModePreferences(Context context,ClaimBackend claims) {
        if (context == null) throw new IllegalArgumentException("Context required");
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.claims=claims;
    }
    /** Package-private injectable seam for host storage-fault tests; no production fake backend. */
    PrintModePreferences(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("Preferences required");
        this.preferences = preferences;
        claims=null;
    }
    @Override public AutoPrintPolicy.Mode loadMode() {
        synchronized (PROCESS_LOCK) {
                String value = preferences.getString(MODE, AutoPrintPolicy.Mode.MANUAL.name());
                if(!AutoPrintPolicy.Mode.MANUAL.name().equals(value)&&!AutoPrintPolicy.Mode.AUTOMATIC.name().equals(value))throw new IllegalStateException("Invalid print mode");
                return AutoPrintPolicy.Mode.AUTOMATIC.name().equals(value)
                        ? AutoPrintPolicy.Mode.AUTOMATIC : AutoPrintPolicy.Mode.MANUAL;
        }
    }
    @Override public boolean loadEnabled(){synchronized(PROCESS_LOCK){
        if(FAILED.contains(preferences))throw new IllegalStateException("Print setting write was unconfirmed");
        return preferences.getBoolean(ENABLED,true);
    }}
    @Override public boolean saveEnabled(boolean enabled){synchronized(PROCESS_LOCK){
        try{if(preferences.edit().putBoolean(ENABLED,enabled).commit()){FAILED.remove(preferences);return true;}}
        catch(RuntimeException ignored){/* SharedPreferences may have changed its cache before failure. */}
        FAILED.add(preferences);
        try{preferences.edit().putBoolean(ENABLED,false).commit();}catch(RuntimeException ignored){/* Fail closed in policy and other in-process owners. */}
        return false;
    }}
    @Override public boolean saveMode(AutoPrintPolicy.Mode mode) {
        if (mode == null) throw new IllegalArgumentException("Mode required");
        synchronized (PROCESS_LOCK) {
            String previous;
            try{previous=loadMode().name();}catch(RuntimeException ignored){FAILED.add(preferences);return false;}
            try {
                if (preferences.edit().putString(MODE, mode.name()).commit()) return true;
            } catch (RuntimeException ignored) { /* commit may already have changed the memory cache */ }
            // SharedPreferences changes its memory cache even if disk commit fails. Restore the
            // previous effective value so another in-process owner cannot read a failed enable.
            try { preferences.edit().putString(MODE, previous).commit(); }
            catch (RuntimeException ignored) { /* policy retains old mode and revokes fresh permission */ }
            FAILED.add(preferences);
            return false;
        }
    }
    @Override public AutoPrintPolicy.Claim claim(String receiptKey) {
        if (!validReceiptKey(receiptKey))
            return AutoPrintPolicy.Claim.FAILED;
        synchronized (PROCESS_LOCK) {
            try {
                String key = "reserved_" + digest(receiptKey);
                // Even an unknown/corrupt existing value is not permission to resubmit.
                if (preferences.contains(key)) return AutoPrintPolicy.Claim.ALREADY_CLAIMED;
                if(claims!=null)return claims.reserve(key.substring("reserved_".length()));
                boolean saved = preferences.edit().putString(key, RESERVED).commit();
                return saved ? AutoPrintPolicy.Claim.CLAIMED : AutoPrintPolicy.Claim.FAILED;
            } catch (Exception | LinkageError ignored) { return AutoPrintPolicy.Claim.FAILED; }
        }
    }
    public static java.util.Set<String> legacyReservations(Context app)throws java.io.IOException {
        java.util.Set<String> out=new java.util.HashSet<>();
        for(String key:app.getApplicationContext().getSharedPreferences(PREFERENCES_NAME,Context.MODE_PRIVATE).getAll().keySet())if(key.startsWith("reserved_")){String hash=key.substring("reserved_".length());if(!hash.matches("[0-9a-f]{64}"))throw new java.io.IOException("Invalid existing reservation name");out.add(hash);}
        return out;
    }
    /** Both explicit business namespaces use the same durable reservation, never arbitrary strings. */
    private static boolean validReceiptKey(String key) {
        if(key==null||key.length()>512)return false;
        if(key.matches("(TEST|LIVE):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))return true;
        String prefix="MANUAL_PASSPORT|";
        if(!key.startsWith(prefix))return false;
        String value=key.substring(prefix.length());int modeEnd=value.indexOf('|'),batchEnd=value.indexOf('|',modeEnd+1),recordStart=value.lastIndexOf('|');
        if(modeEnd<1||batchEnd<=modeEnd||recordStart<=batchEnd)return false;
        String mode=value.substring(0,modeEnd),batch=value.substring(modeEnd+1,batchEnd),bib=value.substring(batchEnd+1,recordStart),record=value.substring(recordStart+1);
        String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        return (mode.equals("TEST")||mode.equals("LIVE"))&&batch.matches(uuid)&&record.matches(uuid)
                &&!bib.isEmpty()&&bib.length()<=80&&bib.equals(bib.trim())&&!bib.matches("(?s).*[\\p{Cntrl}].*");
    }
    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }
}
