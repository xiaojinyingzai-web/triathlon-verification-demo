package com.triathlon.prep.nextday.host;

import com.triathlon.prep.core.nextday.FaceLibrary;
import com.triathlon.prep.core.nextday.FaceLibrarySelection;
import java.io.IOException;
import java.util.Objects;

/** Sole Host worker prepares a separate bank before atomically selecting it. */
public final class FaceBankSwitch {
 public enum State { IDLE, STAGING, COMMITTED, FAILED_RESTORED, BLOCKED_RESTORE }
 public interface Backend {
  /** Release the old index, prepare/import the isolated source bank, and verify its index. */
  void stage(FaceLibrary.Snapshot source,FaceLibrary.MutationGuard guard)throws IOException;
  /** Restore the previous bank and its index; null means no previously selected bank. */
  void restore(FaceLibrary.Scope previous)throws IOException;
  /** Hide readiness and refuse FACE actions until explicit successful preparation. */
  void block();
 }
 private final FaceLibrarySelection selection;private final Backend backend;private final Object authorityLock;
 private volatile State state=State.IDLE;
 public FaceBankSwitch(FaceLibrarySelection selection,Backend backend,Object authorityLock){
  this.selection=Objects.requireNonNull(selection);this.backend=Objects.requireNonNull(backend);this.authorityLock=Objects.requireNonNull(authorityLock);
 }
 public State state(){return state;}
 public void restore(FaceLibrary.MutationGuard guard)throws IOException{
  Objects.requireNonNull(guard);FaceLibrary.Scope previous=selection.current(),target=selection.previous();
  if(target==null)throw new IOException("No previous bank");selection.validate(target);
  synchronized(authorityLock){if(!guard.current())throw new IOException("Restore revoked");}
  state=State.STAGING;
  try{backend.restore(target);synchronized(authorityLock){if(!guard.current())throw new IOException("Restore revoked before selection");selection.restore(guard);}state=State.COMMITTED;}
  catch(IOException|RuntimeException failure){
   try{backend.restore(previous);state=State.FAILED_RESTORED;}
   catch(IOException|RuntimeException restoration){state=State.BLOCKED_RESTORE;try{backend.block();}catch(RuntimeException blocking){restoration.addSuppressed(blocking);}failure.addSuppressed(restoration);}
   if(failure instanceof IOException)throw (IOException)failure;throw new IOException("Bank restore failed",failure);
  }
 }
 public void confirm(FaceLibrary.Snapshot source,FaceLibrary.MutationGuard guard)throws IOException{
  Objects.requireNonNull(source);Objects.requireNonNull(guard);
  selection.validate(source.scope);
  FaceLibrary.Scope previous=selection.current();
  if(source.scope.equals(previous))throw new IOException("Same-bank import requires its existing atomic replacement path");
  synchronized(authorityLock){if(!guard.current())throw new IOException("Source selection revoked");}
  state=State.STAGING;
  try{
   backend.stage(source,guard);
   synchronized(authorityLock){
    if(!guard.current())throw new IOException("Source selection revoked before commit");
    selection.choose(source.scope,guard);
   }
   state=State.COMMITTED;
  }catch(IOException|RuntimeException failure){
   try{backend.restore(previous);state=State.FAILED_RESTORED;}
   catch(IOException|RuntimeException restoration){state=State.BLOCKED_RESTORE;try{backend.block();}catch(RuntimeException blocking){restoration.addSuppressed(blocking);}failure.addSuppressed(restoration);}
   if(failure instanceof IOException)throw (IOException)failure;
   throw new IOException("Source bank switch failed",failure);
  }
 }
}
