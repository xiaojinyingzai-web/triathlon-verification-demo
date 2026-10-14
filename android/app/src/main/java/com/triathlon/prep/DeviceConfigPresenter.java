package com.triathlon.prep;
import com.triathlon.prep.core.DeviceConfigDraft;import com.triathlon.prep.core.DeviceConfigSession;import com.triathlon.prep.ui.DeviceConfigView;
/** Immutable UI mapping; no file, credentials or execution operations. */
final class DeviceConfigPresenter {
 private DeviceConfigPresenter(){}
 static DeviceConfigView.Model model(boolean simulation,DeviceConfigSession.Snapshot value,String failure,boolean busy,boolean active){
  return model(simulation,value,failure,busy,active,"");
 }
 static DeviceConfigView.Model model(boolean simulation,DeviceConfigSession.Snapshot value,String failure,boolean busy,boolean active,String startupNotice){
  String suffix=simulation||startupNotice==null||startupNotice.isEmpty()?"":" 本次启动："+startupNotice+" 保存草案不重启设备；参数变更后的下次启动需重新确认。";
  if(value==null){DeviceConfigDraft empty=DeviceConfigDraft.empty();return new DeviceConfigView.Model(empty,empty,empty.review(),failure.isEmpty()?DeviceConfigView.State.LOADING:DeviceConfigView.State.FAILED,"",0,simulation,false,false,!busy,(failure.isEmpty()?"正在读取非秘密配置草案。":failure)+suffix);}
  return new DeviceConfigView.Model(value.current,value.draft,value.review,busy?DeviceConfigView.State.SAVING:DeviceConfigView.State.valueOf(value.state.name()),value.draftToken,value.revision,simulation,active&&!busy&&value.saveEnabled,active&&!busy&&value.rollbackEnabled,!busy,value.notice+suffix);
 }
}
