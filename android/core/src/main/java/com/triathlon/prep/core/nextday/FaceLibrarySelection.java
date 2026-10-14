package com.triathlon.prep.core.nextday;

import java.io.IOException;
import java.util.Objects;

/** Selects whole isolated FACE banks without changing the CHECKIN roster. */
public final class FaceLibrarySelection {
 public static final class Choice {
  public final FaceLibrary.Scope current, previous;
  public Choice(FaceLibrary.Scope current, FaceLibrary.Scope previous) {
   this.current=Objects.requireNonNull(current);this.previous=previous;
  }
 }
 public interface Store {
  Choice load() throws IOException;
  /** Atomically replace the selection, retaining the old value on failure. */
  void replace(Choice choice) throws IOException;
 }
 private final Store store;
 private final FaceLibrary.Scope compatibility;
 private Choice choice;
 private final FaceLibrary.Scope fallback;
 public FaceLibrarySelection(Store store, FaceLibrary.Scope compatibility,
                             FaceLibrary.Scope fallback) throws IOException {
  this.store=Objects.requireNonNull(store);this.compatibility=Objects.requireNonNull(compatibility);
  this.fallback=fallback;
  if(fallback!=null)requireCompatible(fallback);
  Choice loaded=store.load();
  if(loaded!=null){requireCompatible(loaded.current);if(loaded.previous!=null)requireCompatible(loaded.previous);}
  choice=loaded;
 }
 public synchronized FaceLibrary.Scope current(){return choice==null?fallback:choice.current;}
 public synchronized FaceLibrary.Scope previous(){return choice==null?null:choice.previous;}
 /** Check a proposed bank before a Host releases or stages any index. */
 public void validate(FaceLibrary.Scope target)throws IOException{requireCompatible(target);}
 /** Caller holds its authority commit lock across this operation to serialize revocation. */
 public synchronized void choose(FaceLibrary.Scope target, FaceLibrary.MutationGuard guard) throws IOException {
  requireCompatible(target);Objects.requireNonNull(guard);
  if(!guard.current())throw new IOException("Selection revoked");
  if(target.equals(current()))return;
  Choice replacement=new Choice(target,current());
  store.replace(replacement);
  choice=replacement;
 }
 public synchronized void restore(FaceLibrary.MutationGuard guard) throws IOException {
  FaceLibrary.Scope prior=previous();
  if(prior==null)throw new IOException("No previous FACE bank");
  choose(prior,guard);
 }
 private void requireCompatible(FaceLibrary.Scope scope) throws IOException {
  if(scope==null||scope.mode!=compatibility.mode||!scope.modelFingerprint.equals(compatibility.modelFingerprint)
    ||!scope.featureType.equals(compatibility.featureType)||scope.featureLength!=compatibility.featureLength)
   throw new IOException("Incompatible FACE bank");
 }
}
