package com.triathlon.prep.core.nextday;

import com.triathlon.prep.core.Verification;
import java.io.IOException;
import java.util.*;

/** One active durable version and one matching index. SDK execution belongs to its serial adapter. */
public final class FaceLibrary {
 public static final int MAX_PEOPLE=50000;
 public enum Status { EMPTY, LOADING, READY, DIRTY, CORRUPT, INCOMPATIBLE }
 public static final class Scope {
  public final Verification.Mode mode;public final String batchId,modelFingerprint,featureType;public final int featureLength;
  public Scope(Verification.Mode mode,String batchId,String modelFingerprint,String featureType,int featureLength){
   if(mode==null||!uuid(batchId)||modelFingerprint==null||!modelFingerprint.matches("[a-f0-9]{64}")||featureType==null||!featureType.matches("[A-Z0-9_]{1,64}")||featureLength!=512)throw new IllegalArgumentException("Invalid face library scope");
   this.mode=mode;this.batchId=batchId;this.modelFingerprint=modelFingerprint;this.featureType=featureType;this.featureLength=featureLength;
  }
  @Override public boolean equals(Object other){if(!(other instanceof Scope))return false;Scope s=(Scope)other;return mode==s.mode&&batchId.equals(s.batchId)&&modelFingerprint.equals(s.modelFingerprint)&&featureType.equals(s.featureType)&&featureLength==s.featureLength;}
  @Override public int hashCode(){return Objects.hash(mode,batchId,modelFingerprint,featureType,featureLength);}
 }
 public static final class Entry {
  public final int id;public final String bib,name,group;public final long enrolledAt;private final byte[] feature;
  public Entry(int id,String bib,String name,String group,byte[] feature,long enrolledAt){
   if(id<=0||!text(bib,64,false)||!text(name,128,false)||!text(group,128,true)||feature==null||feature.length!=512||enrolledAt<0)throw new IllegalArgumentException("Invalid enrolled person");
   this.id=id;this.bib=bib;this.name=name;this.group=group;this.feature=feature.clone();this.enrolledAt=enrolledAt;
  }
  public byte[] feature(){return feature.clone();}
 }
 public static final class Snapshot {
  public final String revision;public final Scope scope;public final List<Entry> entries;
  public Snapshot(String revision,Scope scope,List<Entry> entries){
   if(!uuid(revision)||scope==null||entries==null||entries.size()>MAX_PEOPLE)throw new IllegalArgumentException("Invalid library snapshot");
   Set<Integer> ids=new HashSet<>();Set<String> bibs=new HashSet<>();List<Entry> owned=new ArrayList<>(entries.size());
   for(Entry e:entries){if(e==null||e.feature.length!=scope.featureLength||!ids.add(e.id)||!bibs.add(e.bib))throw new IllegalArgumentException("Ambiguous enrolled person mapping");owned.add(e);}
   this.revision=revision;this.scope=scope;this.entries=Collections.unmodifiableList(owned);
  }
 }
 public interface Store {
  Snapshot load()throws IOException;
  void stage(Snapshot candidate)throws IOException;
  /** Atomic: failure must leave the old active pointer intact. */
  void activate(String candidateRevision)throws IOException;
  /** Removes only an inactive candidate, never the active version. */
  void discard(String candidateRevision)throws IOException;
 }
 public interface Index {void clear()throws IOException;void rebuild(Snapshot snapshot)throws IOException;}
 public interface Mutation {void run()throws IOException;}
 public interface MutationGuard {
  boolean current();
  /** Host may serialize only final activation with immediate revocation. */
  default void commit(Mutation mutation)throws IOException{if(!current())throw new IOException("Library mutation revoked before activation");mutation.run();}
 }
 private final Scope scope;private final Store store;private final Index index;private final Object commitLock;private Snapshot current;private Status status=Status.LOADING;
 private boolean activePersisted;private String retiredRevision;
 public FaceLibrary(Scope scope,Store store,Index index){this(scope,store,index,null);}
 /** Module owners supply their immediate-revocation lock for every upsert/import activation. */
 public FaceLibrary(Scope scope,Store store,Index index,Object commitLock){if(scope==null||store==null||index==null)throw new IllegalArgumentException("Explicit library adapters required");this.scope=scope;this.store=store;this.index=index;this.commitLock=commitLock;current=empty();}
 private Snapshot empty(){return new Snapshot(UUID.randomUUID().toString(),scope,Collections.emptyList());}
 public synchronized Status status(){return status;}
 public synchronized Snapshot snapshot(){return current;}
 /** A committed library remains usable; subsequent writes wait for this cleanup to recover. */
 public synchronized boolean cleanupFailed(){return retiredRevision!=null;}
 public synchronized void retryCleanup()throws IOException{if(retiredRevision!=null){store.discard(retiredRevision);retiredRevision=null;}}
 public synchronized void load()throws IOException {
  status=Status.LOADING;current=empty();activePersisted=false;
  try{Snapshot saved=store.load();index.clear();if(saved==null){status=Status.EMPTY;return;}if(!scope.equals(saved.scope)){status=Status.INCOMPATIBLE;throw new IOException("Library scope mismatch");}index.rebuild(saved);current=saved;activePersisted=true;status=current.entries.isEmpty()?Status.EMPTY:Status.READY;}
  catch(IOException e){if(status==Status.LOADING)status=Status.DIRTY;throw e;}
 }
 public synchronized Snapshot upsert(Entry entry)throws IOException {
  return upsert(entry,()->true);
 }
 public synchronized Snapshot upsert(Entry entry,MutationGuard guard)throws IOException {
  if(guard==null)throw new IllegalArgumentException("Explicit mutation guard required");
  usable();List<Entry> proposed=new ArrayList<>(current.entries);boolean replaced=false;
  for(int i=0;i<proposed.size();i++){Entry old=proposed.get(i);if(old.bib.equals(entry.bib)){if(old.id!=entry.id)throw new IllegalArgumentException("Existing person ID cannot change");proposed.set(i,entry);replaced=true;break;}}
  if(!replaced)proposed.add(entry);return replace(new Snapshot(UUID.randomUUID().toString(),scope,proposed),guard);
 }
 public synchronized Snapshot importSnapshot(Snapshot imported)throws IOException {return importSnapshot(imported,()->true);}
 public synchronized Snapshot importSnapshot(Snapshot imported,MutationGuard guard)throws IOException {usable();if(imported==null||!scope.equals(imported.scope)||guard==null)throw new IOException("Imported library scope/authority mismatch");return replace(new Snapshot(UUID.randomUUID().toString(),scope,imported.entries),guard);}
 private void usable()throws IOException{if(status!=Status.READY&&status!=Status.EMPTY)throw new IOException("Library index not ready");}
 private Snapshot replace(Snapshot candidate)throws IOException {
  return replace(candidate,()->true);
 }
 private Snapshot replace(Snapshot candidate,MutationGuard guard)throws IOException {
  if(!guard.current())throw new IOException("Library mutation revoked before staging");
  retryCleanup(); // A known failed retirement cannot grow another full historical snapshot.
  Snapshot previous=current;status=Status.LOADING;boolean staged=false;
  try{store.stage(candidate);staged=true;index.clear();index.rebuild(candidate);activate(candidate,guard);current=candidate;status=candidate.entries.isEmpty()?Status.EMPTY:Status.READY;
   if(activePersisted)retiredRevision=previous.revision;activePersisted=true;
   try{retryCleanup();}catch(IOException cleanup){/* Postcommit maintenance must not roll back an already active library. */}
   return current;}
  catch(IOException error){current=previous;try{index.clear();index.rebuild(previous);status=previous.entries.isEmpty()?Status.EMPTY:Status.READY;}catch(IOException restore){status=Status.DIRTY;error.addSuppressed(restore);}if(staged)try{store.discard(candidate.revision);}catch(IOException cleanup){error.addSuppressed(cleanup);}throw error;}
 }
 private void activate(Snapshot candidate,MutationGuard guard)throws IOException{
  if(commitLock==null){guard.commit(()->store.activate(candidate.revision));return;}
  synchronized(commitLock){guard.commit(()->store.activate(candidate.revision));}
 }
 static boolean uuid(String value){if(value==null)return false;try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException e){return false;}}
 static boolean text(String value,int max,boolean empty){if(value==null||value.length()>max||(!empty&&value.trim().isEmpty())||!value.equals(value.trim()))return false;for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return false;return true;}
}
