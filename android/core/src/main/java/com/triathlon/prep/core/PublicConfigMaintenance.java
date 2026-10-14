package com.triathlon.prep.core;
import java.io.IOException;import java.util.*;
/** Public fields only. Merges into the existing revisioned store; never grants execution. */
public final class PublicConfigMaintenance {
 private final FileDeviceConfigStore store;
 public PublicConfigMaintenance(FileDeviceConfigStore store){this.store=Objects.requireNonNull(store);}
 public FileDeviceConfigStore.Snapshot read()throws IOException{return store.load();}
 public FileDeviceConfigStore.Snapshot apply(Map<String,String> fields,long expectedRevision)throws IOException{
  if(expectedRevision<0||fields==null||fields.isEmpty()||fields.size()>DeviceConfigDraft.Field.values().length)throw new IOException("Invalid public patch");
  // Validate the whitelist before touching the saved snapshot.
  DeviceConfigDraft.from(fields);
  synchronized(store){
   FileDeviceConfigStore.Snapshot old=store.load();if(old.revision!=expectedRevision)throw new IOException("Stale public configuration revision");
   Map<String,String> merged=new LinkedHashMap<>(old.current.toMap());merged.putAll(fields);DeviceConfigDraft draft=DeviceConfigDraft.from(merged);
   if(!draft.review().validForSave)throw new IOException("Invalid public parameter values");
   if(draft.equals(old.current))return old;
   return store.save(draft,expectedRevision);
  }
 }
}
