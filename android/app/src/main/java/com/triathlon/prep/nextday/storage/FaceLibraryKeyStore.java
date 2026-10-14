package com.triathlon.prep.nextday.storage;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.triathlon.prep.core.Verification;
import java.io.IOException;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Local at-rest key only. Host must keep TEST/LIVE databases and keys in matching private namespaces. */
public final class FaceLibraryKeyStore {
 private FaceLibraryKeyStore() {}
 public static synchronized SecretKey loadOrCreate(Context context, Verification.Mode mode) throws IOException {
  if(context==null||mode==null)throw new IllegalArgumentException("Explicit context and mode required");
  String alias=context.getApplicationContext().getPackageName()+".nextday.library.v1."+mode.name();
  try {
   KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
   if(store.containsAlias(alias)) {
    java.security.Key key=store.getKey(alias,null);
    if(!(key instanceof SecretKey))throw new IOException("Local face key unavailable");
    return (SecretKey)key;
   }
   // If a key is lost, generating one does NOT make old data usable: authenticated load fails closed.
   KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
   generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setRandomizedEncryptionRequired(true).build());
   return generator.generateKey();
  } catch(IOException e){throw e;}catch(Exception e){throw new IOException("Local face key initialization failed",e);}
 }
}
