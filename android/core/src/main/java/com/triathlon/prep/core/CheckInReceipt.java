package com.triathlon.prep.core;

import java.util.*;

/** Session-bound receipt content. No printer, identifier or photo API is exposed. */
public final class CheckInReceipt {
 public enum Meaning { COMPLETED, REQUIRED, NOT_REQUIRED, PENDING, CONFIRM }
 public enum Item { IDENTITY, DECLARATION, SWIMMING, INSURANCE, BAG }
 public static final class Step {
  public final Item item;public final String label,note;public final boolean checked;public final Meaning meaning;
  private Step(Item item,String label,String note,boolean checked,Meaning meaning){this.item=item;this.label=label;this.note=note;this.checked=checked;this.meaning=meaning;}
 }
 public static final class Document {
  public final String sessionId,rosterBatchId,eventName,eventHeading,title,name,team,group,bib;
  public final boolean simulation;public final List<Step> steps;
  private final Verification owner;private final Verification.Record result;private final RaceRoster.Athlete athlete;
  private Document(Verification owner,Verification.Snapshot s,boolean simulation,List<Step> steps){
   this.owner=owner;result=s.result;athlete=s.athlete;this.simulation=simulation;
   sessionId=s.sessionId;rosterBatchId=s.rosterBatchId;eventName=s.eventName;eventHeading=eventName;title="报到流程单";
   name=s.athlete.name;team=s.athlete.team;group=s.athlete.group;bib=s.athlete.bib;this.steps=Collections.unmodifiableList(new ArrayList<>(steps));
  }
  private Document(Document source,String heading){
   owner=source.owner;result=source.result;athlete=source.athlete;simulation=source.simulation;steps=source.steps;
   sessionId=source.sessionId;rosterBatchId=source.rosterBatchId;eventName=source.eventName;eventHeading=heading;
   title=source.title;name=source.name;team=source.team;group=source.group;bib=source.bib;
  }
 }
 public static final int MAX_HEADING_CODE_POINTS=40;
 /** Empty override retains imported provenance; line/control injection is never a printable name. */
 public static String normalizeHeading(String value){
  String raw=value==null?"":value;
  for(int i=0;i<raw.length();){int cp=raw.codePointAt(i);i+=Character.charCount(cp);if(Character.isISOControl(cp)||cp==0x2028||cp==0x2029)throw new IllegalArgumentException("小票赛事名称不能包含换行或控制字符");}
  int start=0,end=raw.length();
  while(start<end){int cp=raw.codePointAt(start);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))break;start+=Character.charCount(cp);}
  while(end>start){int cp=raw.codePointBefore(end);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))break;end-=Character.charCount(cp);}
  String text=raw.substring(start,end);
  if(text.codePointCount(0,text.length())>MAX_HEADING_CODE_POINTS)throw new IllegalArgumentException("小票赛事名称最多40个字符");
  return text;
 }
 public static Document withEventHeading(Document source,String override){
  if(source==null)return null;String heading=normalizeHeading(override);
  return new Document(source,heading.isEmpty()?source.eventName:heading);
 }
 /** Caller supplies actual printable width and glyph metrics; 58mm does not imply a dot width. */
 public static final class Layout {
  public final int printableDots,latinGlyphDots,wideGlyphDots;
  public Layout(int printableDots,int latinGlyphDots,int wideGlyphDots){
   if(printableDots<1||printableDots>4096||latinGlyphDots<1||wideGlyphDots<latinGlyphDots||wideGlyphDots>printableDots/4)throw new IllegalArgumentException("Invalid logical receipt metrics");
   this.printableDots=printableDots;this.latinGlyphDots=latinGlyphDots;this.wideGlyphDots=wideGlyphDots;
  }
 }
 private CheckInReceipt(){}
 public static Document prepare(Verification owner,String expectedSession){return prepare(owner,expectedSession,false);}
 public static Document preparePreview(Verification owner,String expectedSession){return prepare(owner,expectedSession,true);}
 private static Document prepare(Verification owner,String expectedSession,boolean simulation){
  if(owner==null)return null;
  synchronized(owner){
   Verification.Snapshot s=owner.snapshot();
   if(!eligible(s,expectedSession)||simulation!=(s.mode==Verification.Mode.TEST))return null;
   List<Step> steps=new ArrayList<>();
   steps.add(new Step(Item.IDENTITY,"身份核验",simulation?"模拟核验":"本次核验通过",true,Meaning.COMPLETED));
   steps.add(new Step(Item.DECLARATION,"签署声明书","待办理",false,Meaning.PENDING));
   RaceRoster.SwimTest swim=s.athlete.swimTest;
   steps.add(new Step(Item.SWIMMING,"泳池测试",swim==RaceRoster.SwimTest.MARKED?"名单有标记，不勾选":swim==RaceRoster.SwimTest.EXPLICIT_BLANK?"需测试":"待确认",swim==RaceRoster.SwimTest.EXPLICIT_BLANK,swim==RaceRoster.SwimTest.MARKED?Meaning.NOT_REQUIRED:swim==RaceRoster.SwimTest.EXPLICIT_BLANK?Meaning.REQUIRED:Meaning.CONFIRM));
   steps.add(new Step(Item.INSURANCE,"保险核验","待办理",false,Meaning.PENDING));
   steps.add(new Step(Item.BAG,"赛事包发放","待办理",false,Meaning.PENDING));
   return new Document(owner,s,simulation,steps);
  }
 }
 private static boolean eligible(Verification.Snapshot s,String expectedSession){
  Verification.Record r=s.result;
  return expectedSession!=null&&!expectedSession.isEmpty()&&expectedSession.equals(s.sessionId)
   &&s.stage==Verification.Stage.FINISHED&&s.saveState==Verification.SaveState.SAVED
   &&s.membership==Verification.Membership.MATCHED&&s.athlete!=null&&!s.eventName.isEmpty()
   &&r!=null&&r.sessionId.equals(s.sessionId)&&r.rosterBatchId.equals(s.rosterBatchId)
   &&r.mode==s.mode&&r.membership==Verification.Membership.MATCHED
   &&r.eventVerdict==Verification.Verdict.PASSED&&r.verdict==Verification.Verdict.PASSED
   &&r.error==Verification.Error.NONE&&r.liveness==Verification.Liveness.PASSED
   &&r.comparisonSource.equals(s.mode==Verification.Mode.TEST?"TEST_FIXTURE_PAIR":"CURRENT_CHIP_TO_CURRENT_CAPTURE");
 }
 private static boolean current(Verification owner,Document d){
  if(d==null||owner==null||d.owner!=owner)return false;
  Verification.Snapshot s=owner.snapshot();
  return eligible(s,d.sessionId)&&s.result==d.result&&s.athlete==d.athlete
   &&s.rosterBatchId.equals(d.rosterBatchId)&&d.simulation==(s.mode==Verification.Mode.TEST);
 }
 public static boolean canPrint(Verification owner,Document document){
  if(owner==null)return false;
  synchronized(owner){return document!=null&&!document.simulation&&current(owner,document);}
 }
 /** Rechecks the current controller rather than accepting a retained snapshot or historical record. */
 public static List<String> render(Verification owner,Document document,Layout layout){
  if(owner==null||layout==null)throw new IllegalArgumentException("Controller and layout required");
  synchronized(owner){
   if(!current(owner,document))throw new IllegalStateException("No current receipt");
   List<String> lines=new ArrayList<>();
   if(document.simulation)wrap(lines,"模拟样张 / 非有效凭条",layout);
   wrap(lines,document.eventHeading,layout);wrap(lines,document.title,layout);
   wrap(lines,"姓名："+document.name,layout);
   wrap(lines,"俱乐部/团体："+(document.team.isEmpty()?"未提供":document.team),layout);
   wrap(lines,"组别："+document.group,layout);wrap(lines,"比赛号码："+document.bib,layout);
   for(Step step:document.steps)wrap(lines,(step.checked?"[√] ":"[ ] ")+step.label+"（"+step.note+"）",layout);
   if(document.simulation)wrap(lines,"仅供模拟测试，不能作为报到凭证",layout);
   return Collections.unmodifiableList(lines);
  }
 }
 private static void wrap(List<String> lines,String text,Layout layout){
  StringBuilder line=new StringBuilder();int used=0;
  for(int offset=0;offset<text.length();){int cp=text.codePointAt(offset);offset+=Character.charCount(cp);
   int dots=cp<128?layout.latinGlyphDots:layout.wideGlyphDots;
   if(used+dots>layout.printableDots&&line.length()>0){lines.add(line.toString());line.setLength(0);used=0;}
   line.appendCodePoint(cp);used+=dots;
  }
  if(line.length()>0)lines.add(line.toString());
 }
}
