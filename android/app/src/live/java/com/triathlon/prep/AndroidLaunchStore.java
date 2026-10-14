package com.triathlon.prep;

import android.content.Context;import android.security.keystore.KeyGenParameterSpec;import android.security.keystore.KeyProperties;
import com.triathlon.prep.core.*;import java.io.*;import java.security.KeyStore;import javax.crypto.*;

/** Private, non-exported provisioning boundary. No assets, preferences, intents or public editor secrets. */
public final class AndroidLaunchStore {
    private static final Object KEY_LOCK=new Object();
    private AndroidLaunchStore(){}
    static EncryptedLaunchStore open(Context context){
        Context app=context.getApplicationContext();String alias=app.getPackageName()+".private-launch.v1";
        return new EncryptedLaunchStore(new File(app.getNoBackupFilesDir(),"production-launch"),write->{
            synchronized(KEY_LOCK){
                KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
                if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
                if(!write)return null;
                KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
                gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
                return gen.generateKey();
            }
        });
    }
    /** The private provisioning controller calls this after manufacturer/service/operator confirmation.
     * It is not called during startup, public draft save, or configuration rendering. */
    public static void saveConfirmed(Context app,PrivateLaunchSettings settings)throws IOException{
        if(app==null||settings==null)throw new IOException("Confirmed private settings required");
        try{
            DeviceConfigDraft current=new FileDeviceConfigStore(new File(app.getFilesDir(),"device-config")).load().current;
            if(!LiveProductionConfigLoader.map(current,settings).ready())throw new IOException();
            open(app).save(settings);
        }catch(Exception error){throw new IOException("Confirmed private provisioning failed");}
    }
}
