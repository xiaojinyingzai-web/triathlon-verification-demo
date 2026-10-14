package com.triathlon.prep.core;
import java.io.IOException;import java.util.UUID;
/** Serialized public-draft editor. Disk revision and UI acknowledgment revision are separate. */
public final class DeviceConfigSession {
 public enum State {LOADING,EMPTY,READY,SAVING,SAVED,FAILED,ROLLED_BACK}
 public static final class Snapshot {
  public final DeviceConfigDraft current,draft;public final DeviceConfigDraft.Review review;public final State state;
  public final String draftToken,notice;public final long revision;public final boolean saveEnabled,rollbackEnabled;
  Snapshot(DeviceConfigSession s){current=s.current;draft=s.draft;review=draft.review();state=s.state;draftToken=s.token;notice=s.notice;revision=s.revision;saveEnabled=s.loaded&&review.validForSave&&!draft.equals(current);rollbackEnabled=s.loaded&&s.previous!=null;}
 }
 private final FileDeviceConfigStore store;private DeviceConfigDraft current=DeviceConfigDraft.empty(),draft=current,previous;
 private State state=State.LOADING;private String token=UUID.randomUUID().toString(),notice="正在读取非秘密配置草案。";private long diskRevision,revision;private boolean loaded;
 public DeviceConfigSession(FileDeviceConfigStore store){if(store==null)throw new IllegalArgumentException("Store required");this.store=store;reload();}
 public synchronized Snapshot snapshot(){return new Snapshot(this);}
 private void acknowledge(){revision++;token=UUID.randomUUID().toString();}
 private void accept(FileDeviceConfigStore.Snapshot value,State next,String message){current=value.current;draft=current;previous=value.previous;diskRevision=value.revision;loaded=true;state=next;notice=message;acknowledge();}
 public synchronized void reload(){try{FileDeviceConfigStore.Snapshot value=store.load();accept(value,value.revision==0?State.EMPTY:State.READY,"仅载入非秘密草案；硬件、网络和真实核验未启用，凭据与许可本批未检查。");}catch(IOException e){loaded=false;state=State.FAILED;notice="配置草案读取失败，未采用默认配置或启用执行；请检查私有存储。";acknowledge();}}
 public synchronized void edit(DeviceConfigDraft value){if(value==null)throw new IllegalArgumentException("Draft required");draft=value;if(loaded)state=State.READY;notice=loaded?"候选尚未保存；保存草案不会启用硬件或网络。":notice;acknowledge();}
 public synchronized boolean save(String expectedToken,long expectedRevision){
  if(!loaded||expectedRevision!=revision||!token.equals(expectedToken)||!snapshot().saveEnabled){notice="候选无效、未修改或页面已过期；未保存或启用执行。";acknowledge();return false;}
  state=State.SAVING;try{accept(store.save(draft,diskRevision),State.SAVED,"非秘密配置草案已保存；硬件、网络和真实核验未启用，凭据与许可本批未检查。");return true;}
  catch(IOException e){state=State.FAILED;notice="配置保存失败，已确认草案保持；候选可检查后重试，执行未启用。";acknowledge();return false;}
 }
 public synchronized boolean rollback(long expectedRevision){
  if(!loaded||expectedRevision!=revision||previous==null){notice="没有可恢复的已确认版本或页面已过期；草案保持。";acknowledge();return false;}
  state=State.SAVING;try{accept(store.rollback(diskRevision),State.ROLLED_BACK,"已恢复上一份非秘密草案；记录与名单保持，执行未启用。");return true;}
  catch(IOException e){state=State.FAILED;notice="恢复失败，已确认草案保持；执行未启用。";acknowledge();return false;}
 }
}
