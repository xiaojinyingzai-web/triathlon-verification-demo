package com.triathlon.prep.nextday.host;
import com.triathlon.prep.core.*;import com.triathlon.prep.core.nextday.*;import com.triathlon.prep.ui.NextDayFaceView;
import java.util.*;
/** Worker-owned action/presentation authority; only saved workflow completion exposes a person. */
public final class NextDayScreenSession {
 private final Verification.Mode mode;private boolean enabled,previewReady;private volatile boolean running;
 private long revision=1,libraryNumber,surfaceRevision;private int bufferWidth,bufferHeight;
 private String operationId=UUID.randomUUID().toString(),viewToken=UUID.randomUUID().toString(),token=UUID.randomUUID().toString(),backendKey="",seenLibrary="";
 private FaceLibrary library;private FaceEngine engine;private FaceVerificationWorkflow.Result result;
 private volatile NextDayFaceView.Model offered;
 public NextDayScreenSession(Verification.Mode mode){if(mode==null)throw new IllegalArgumentException();this.mode=mode;}
 public long revision(){return revision;}public String operationId(){return operationId;}public String viewToken(){return viewToken;}
 public boolean busy(){return running;}public boolean enabled(){return enabled;}public boolean idleForPreview(){return enabled&&!running&&result==null;}
 public boolean matchesSurfaceToken(String token){NextDayFaceView.Model view=offered;return view!=null&&view.viewToken.equals(token);}
 private void renew(){revision++;token=UUID.randomUUID().toString();}
 public void invalidate(){renew();operationId=UUID.randomUUID().toString();viewToken=UUID.randomUUID().toString();result=null;running=false;previewReady=false;offered=null;}
 public void backend(FaceLibrary library,FaceEngine engine,boolean preview,long surface,int width,int height){
  String key=library==null?"":library.snapshot().revision+"/"+library.status()+"/"+(engine==null?"":engine.readiness())+"/"+preview+"/"+surface+"/"+width+"/"+height;
  String currentLibrary=library==null?"":library.snapshot().revision;
  if(!backendKey.equals(key)){if(!seenLibrary.isEmpty()&&!seenLibrary.equals(currentLibrary))result=null;seenLibrary=currentLibrary;backendKey=key;libraryNumber++;renew();}
  this.library=library;this.engine=engine;previewReady=preview;surfaceRevision=surface;bufferWidth=width;bufferHeight=height;
 }
 private boolean ready(){return enabled&&library!=null&&engine!=null&&library.status()==FaceLibrary.Status.READY&&engine.readiness()==FaceEngine.Readiness.READY&&library.snapshot().scope.mode==mode&&engine.scope().equals(library.snapshot().scope);}
 public NextDayFaceView.PrimaryAction action(){
  if(running)return NextDayFaceView.PrimaryAction.NONE;
  if(result!=null)return result.state==FaceVerificationWorkflow.State.SAVE_FAILED?NextDayFaceView.PrimaryAction.valueOf("RETRY_SAVE"):NextDayFaceView.PrimaryAction.NONE;
  if(!enabled)return NextDayFaceView.PrimaryAction.ENABLE_MODULE;
  if(!ready())return NextDayFaceView.PrimaryAction.RETRY_PREPARATION;
  return mode==Verification.Mode.TEST||previewReady?NextDayFaceView.PrimaryAction.START_CHECK:NextDayFaceView.PrimaryAction.NONE;
 }
 private boolean ticket(String id,String request,long version,boolean active){return active&&operationId.equals(id)&&token.equals(request)&&revision==version;}
 public boolean primary(String id,String request,long version,boolean active){
  if(!ticket(id,request,version,active))return false;NextDayFaceView.PrimaryAction action=action();if(action==NextDayFaceView.PrimaryAction.NONE)return false;
  if(action==NextDayFaceView.PrimaryAction.ENABLE_MODULE||action==NextDayFaceView.PrimaryAction.RETRY_PREPARATION){enabled=true;invalidate();}
  else{running=true;renew();}return true;
 }
 public void completed(FaceVerificationWorkflow.Result done){if(done==null||!done.operationId.equals(operationId))return;result=done;running=false;renew();}
 public boolean next(String id,String request,long version,boolean active){if(!ticket(id,request,version,active)||running||result==null||result.state==FaceVerificationWorkflow.State.SAVE_FAILED)return false;invalidate();return true;}
 public boolean back(String id,String request,long version,boolean active){if(!ticket(id,request,version,active)||running)return false;invalidate();return true;}
 public boolean allowsCancel(String id,String request,long version){NextDayFaceView.Model view=offered;return view!=null&&view.cancelEnabled&&view.operationId.equals(id)&&view.requestToken.equals(request)&&view.revision==version;}
 public boolean manual(String id,String request,long version,boolean active){if(!ticket(id,request,version,active)||result==null||result.decision==null||result.decision.passed())return false;renew();return true;}
 public NextDayFaceView.Model model(RaceRoster roster,boolean active){
  boolean test=mode==Verification.Mode.TEST;NextDayFaceView.State state=!enabled?NextDayFaceView.State.DISABLED:ready()&&(test||previewReady)?NextDayFaceView.State.READY:NextDayFaceView.State.BLOCKED;
  String notice=!enabled?"请先准备刷脸模块并核对本场人脸库。":library==null?"本场人脸库未就绪，请先完成登记或导入。":engine==null||engine.readiness()!=FaceEngine.Readiness.READY?"百度许可、模型或参数未确认，不能开始正式刷脸核验。":library.status()!=FaceLibrary.Status.READY?"本场人脸库未就绪，请先完成登记或导入。":!test&&!previewReady?"等待本次预览稳定，请勿重复操作。":"请由工作人员明确开始本次刷脸核验。";
  NextDayFaceView.Person person=null;Double best=null,second=null;int candidates=0;NextDayFaceView.StepResult quality=NextDayFaceView.StepResult.NOT_RUN,live=quality,recognition=quality;
  if(running){state=NextDayFaceView.State.CAPTURING;notice="正在采集并核验本次现场照，请勿重复操作。";}
  if(result!=null){quality=step(result.quality);live=step(result.liveness);
   if(result.state==FaceVerificationWorkflow.State.COMPLETE&&result.decision!=null){boolean passed=result.decision.passed();state=passed?NextDayFaceView.State.MATCHED:NextDayFaceView.State.MANUAL_REQUIRED;notice=passed?"本次刷脸核验记录已保存。":"本次未自动放行，独立结果已保存，请工作人员处理。";recognition=passed?NextDayFaceView.StepResult.PASSED:NextDayFaceView.StepResult.FAILED;candidates=result.candidates.size();
    if(passed){com.triathlon.prep.core.nextday.decision.FaceDecision.Person p=result.decision.person;person=new NextDayFaceView.Person(p.libraryRevision+":"+p.id,p.bib,p.name,p.group);}
    List<FaceEngine.Candidate> ranked=new ArrayList<>(result.candidates);ranked.sort((a,b)->Double.compare(b.score,a.score));if(!ranked.isEmpty())best=ranked.get(0).score;if(ranked.size()>1)second=ranked.get(1).score;
   }else{state=result.state==FaceVerificationWorkflow.State.CANCELLED?NextDayFaceView.State.CANCELLED:NextDayFaceView.State.FAILED;notice=result.state==FaceVerificationWorkflow.State.SAVE_FAILED?"记录保存失败，尚未放行；请明确重试保存本次核验记录。":"本次核验未完成，请核对条件后重新处理。";}
  }
  if(!active||enabled&&!ready()&&person!=null){state=NextDayFaceView.State.BLOCKED;person=null;best=second=null;candidates=0;}
  NextDayFaceView.LibraryState libraryState=library==null?NextDayFaceView.LibraryState.ABSENT:NextDayFaceView.LibraryState.valueOf(library.status().name());int stored=library==null?0:library.snapshot().entries.size(),indexed=ready()?stored:0;
  NextDayFaceView.PrimaryAction primary=action();boolean next=result!=null&&!running&&result.state!=FaceVerificationWorkflow.State.SAVE_FAILED;
  NextDayFaceView.StepResult captureState=running?NextDayFaceView.StepResult.RUNNING:result==null?NextDayFaceView.StepResult.NOT_RUN:result.state==FaceVerificationWorkflow.State.COMPLETE||result.state==FaceVerificationWorkflow.State.SAVE_FAILED?NextDayFaceView.StepResult.PASSED:NextDayFaceView.StepResult.UNKNOWN;
  offered=new NextDayFaceView.Model(test,library==null?"未选择人脸库":roster!=null&&roster.batchId.equals(library.snapshot().scope.batchId)?roster.eventName:"来源人脸库",library==null?"未选择批次":library.snapshot().scope.batchId,test?"TEST合成字节标记 · 非真实人脸算法":"百度V9 / LIVE_PHOTO",notice,operationId,viewToken,token,revision,libraryNumber,surfaceRevision,!test&&previewReady,stored,indexed,candidates,state,libraryState,test?NextDayFaceView.Readiness.SIMULATION:ready()?NextDayFaceView.Readiness.READY:NextDayFaceView.Readiness.UNVERIFIED_LICENSE,captureState,quality,live,recognition,best,second,person,primary,active&&primary!=NextDayFaceView.PrimaryAction.NONE,active&&running,active&&next,active&&state==NextDayFaceView.State.MANUAL_REQUIRED,active&&!running,bufferWidth,bufferHeight);
  return offered;
 }
 private static NextDayFaceView.StepResult step(FaceEngine.StepResult result){return result==FaceEngine.StepResult.PASSED?NextDayFaceView.StepResult.PASSED:result==FaceEngine.StepResult.FAILED?NextDayFaceView.StepResult.FAILED:NextDayFaceView.StepResult.UNKNOWN;}
}


