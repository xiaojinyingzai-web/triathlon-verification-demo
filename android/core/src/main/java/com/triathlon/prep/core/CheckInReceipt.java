package com.triathlon.prep.core;

import java.util.*;

/** Session-bound receipt content. No printer, identifier or photo API is exposed. */
public final class CheckInReceipt {
 public enum Meaning { COMPLETED, REQUIRED, NOT_REQUIRED, PENDING, CONFIRM }
 public enum Item { IDENTITY, DECLARATION, SWIMMING, INSURANCE, BAG }
 public static final class Step {
  public final Item item;public final String label,note;public final boolean checked,visible;public final Meaning meaning;
  private Step(Item item,String label,String note,boolean checked,Meaning meaning){this(item,label,note,checked,meaning,true);}
  private Step(Item item,String label,String note,boolean checked,Meaning meaning,boolean visible){this.item=item;this.label=label;this.note=note;this.checked=checked;this.meaning=meaning;this.visible=visible;}
 }
 public static final class Document {
  public final String sessionId,rosterBatchId,eventName,eventHeading,title,name,team,group,bib;
  public final boolean simulation;public final List<Step> steps;
  public final ReceiptTemplate template;public final RaceRoster.SwimTest swimTest;public final boolean showDeepWaterSchedule,unknownDeepWater;
  public final ManualPrintAuthorization manualAuthorization;
  private final Verification owner;private final Verification.Record result;private final RaceRoster.Athlete athlete;
  private Document(Verification owner,Verification.Snapshot s,boolean simulation,List<Step> steps){
   this.owner=owner;result=s.result;athlete=s.athlete;this.simulation=simulation;
   sessionId=s.sessionId;rosterBatchId=s.rosterBatchId;eventName=s.eventName;eventHeading=eventName;title="报到流程单";
   name=s.athlete.name;team=s.athlete.team;group=s.athlete.group;bib=s.athlete.bib;this.steps=Collections.unmodifiableList(new ArrayList<>(steps));
   template=ReceiptTemplate.defaults();swimTest=s.athlete.swimTest;showDeepWaterSchedule=false;unknownDeepWater=swimTest==RaceRoster.SwimTest.UNKNOWN;manualAuthorization=null;
  }
  private Document(Document source,String heading){
   owner=source.owner;result=source.result;athlete=source.athlete;simulation=source.simulation;steps=source.steps;
   sessionId=source.sessionId;rosterBatchId=source.rosterBatchId;eventName=source.eventName;eventHeading=heading;
   title=source.title;name=source.name;team=source.team;group=source.group;bib=source.bib;
   template=source.template;swimTest=source.swimTest;showDeepWaterSchedule=source.showDeepWaterSchedule;unknownDeepWater=source.unknownDeepWater;manualAuthorization=source.manualAuthorization;
  }
  private Document(Document source,ReceiptTemplate template){
   owner=source.owner;result=source.result;athlete=source.athlete;simulation=source.simulation;sessionId=source.sessionId;rosterBatchId=source.rosterBatchId;eventName=source.eventName;
   this.template=Objects.requireNonNull(template);manualAuthorization=source.manualAuthorization;String heading=template.text(ReceiptTemplate.TextKey.EVENT_HEADING);eventHeading=heading.isEmpty()?eventName:heading;title=template.text(ReceiptTemplate.TextKey.SUBTITLE);name=source.name;team=source.team;group=source.group;bib=source.bib;swimTest=source.swimTest;unknownDeepWater=swimTest==RaceRoster.SwimTest.UNKNOWN;
   EnumMap<Item,Step> semantic=new EnumMap<>(Item.class);for(Step step:source.steps)semantic.put(step.item,step);List<Step> ordered=new ArrayList<>();boolean schedule=false;
   for(ReceiptTemplate.ItemSpec spec:template.items){Step original=semantic.get(spec.id);if(original==null)throw new IllegalArgumentException("Missing semantic item");ordered.add(new Step(spec.id,spec.label,spec.id==Item.SWIMMING&&unknownDeepWater?"待确认":"",original.checked,original.meaning,spec.visible));if(spec.id==Item.SWIMMING&&spec.visible&&swimTest==RaceRoster.SwimTest.MARKED)schedule=true;}
   steps=Collections.unmodifiableList(ordered);showDeepWaterSchedule=schedule;
  }
  private Document(RaceRoster.SwimTest state){
   owner=null;result=null;athlete=null;manualAuthorization=null;simulation=true;sessionId="TEMPLATE_SAMPLE";rosterBatchId="";eventName="2026年宜宾铁人三项赛";eventHeading=eventName;title="报道转单";name="合成张小明";team="示例铁人俱乐部";group="男子半程30–44岁组";bib="01051";template=ReceiptTemplate.defaults();swimTest=Objects.requireNonNull(state);unknownDeepWater=state==RaceRoster.SwimTest.UNKNOWN;showDeepWaterSchedule=state==RaceRoster.SwimTest.MARKED;
   steps=Collections.unmodifiableList(Arrays.asList(new Step(Item.IDENTITY,"身份核验","",true,Meaning.COMPLETED),new Step(Item.DECLARATION,"签署声明书","",false,Meaning.PENDING),new Step(Item.SWIMMING,"深水测试",unknownDeepWater?"待确认":"",state==RaceRoster.SwimTest.EXPLICIT_BLANK,state==RaceRoster.SwimTest.MARKED?Meaning.NOT_REQUIRED:state==RaceRoster.SwimTest.EXPLICIT_BLANK?Meaning.REQUIRED:Meaning.CONFIRM),new Step(Item.INSURANCE,"保险核验","",false,Meaning.PENDING),new Step(Item.BAG,"赛事包发放","",false,Meaning.PENDING)));
  }
  private Document(ManualPrintAuthorization authorization){
   owner=null;result=null;manualAuthorization=authorization;athlete=authorization.athlete;simulation=authorization.mode==Verification.Mode.TEST;sessionId=authorization.record.id;rosterBatchId=authorization.record.rosterBatchId;eventName=authorization.eventName;eventHeading=eventName;title="报道转单";name=athlete.name;team=athlete.team;group=athlete.group;bib=athlete.bib;template=ReceiptTemplate.defaults();swimTest=athlete.swimTest;unknownDeepWater=swimTest==RaceRoster.SwimTest.UNKNOWN;showDeepWaterSchedule=swimTest==RaceRoster.SwimTest.MARKED;
   steps=new Document(swimTest).steps;
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
 public static Document withTemplate(Document source,ReceiptTemplate template){return source==null?null:new Document(source,template);}
 /** Explicitly non-session-bound sample; the normal controller gate always rejects it. */
 public static Document sample(ReceiptTemplate template,RaceRoster.SwimTest state){return new Document(new Document(state),template);}
 /** A distinct manual grant never satisfies the automatic owner/result gate. */
 public static Document manual(ManualPrintAuthorization authorization,ReceiptTemplate template){if(authorization==null||!authorization.current())throw new IllegalStateException("Current committed manual authorization required");return new Document(new Document(authorization),template);}
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
   center(lines,document.eventHeading,layout);center(lines,document.title,layout);
   lines.add("");divider(lines,layout);lines.add("");
   field(lines,"姓名：",document.name,layout);lines.add("");
   field(lines,"所属团体：",document.team.isEmpty()?"未提供":document.team,layout);lines.add("");
   field(lines,"参赛组别：",document.group,layout);lines.add("");
   field(lines,"比赛号码：",document.bib,layout);
   lines.add("");divider(lines,layout);lines.add("");
   int ordinal=0;
   for(Step step:document.steps){if(ordinal>0)lines.add("");step(lines,(++ordinal)+". "+step.label,step.checked,layout);}
   lines.add("");divider(lines,layout);
   if(document.simulation){lines.add("");center(lines,"模拟样张 · 非有效凭条",layout);}
   return Collections.unmodifiableList(lines);
  }
 }
 private static int width(String text,Layout layout){
  int dots=0;for(int offset=0;offset<text.length();){int cp=text.codePointAt(offset);offset+=Character.charCount(cp);dots+=cp<128?layout.latinGlyphDots:layout.wideGlyphDots;}return dots;
 }
 private static String spaces(int dots,Layout layout){
  StringBuilder result=new StringBuilder();for(int n=dots/layout.latinGlyphDots;n>0;n--)result.append(' ');return result.toString();
 }
 private static void divider(List<String> lines,Layout layout){
  StringBuilder line=new StringBuilder();for(int n=layout.printableDots/layout.latinGlyphDots;n>0;n--)line.append('-');lines.add(line.toString());
 }
 private static void center(List<String> lines,String text,Layout layout){
  for(String line:chunks(text,layout.printableDots,layout))lines.add(spaces((layout.printableDots-width(line,layout))/2,layout)+line);
 }
 /** Both columns wrap independently; even very narrow configurations preserve every value. */
 private static void field(List<String> lines,String label,String value,Layout layout){
  int latin=layout.latinGlyphDots;
  int wanted=((width("所属团体：",layout)+latin-1)/latin)*latin;
  int labelDots=Math.min(wanted,((layout.printableDots-latin-layout.wideGlyphDots)/latin)*latin);
  List<String> labels=chunks(label,labelDots,layout),values=chunks(value,layout.printableDots-labelDots-latin,layout);
  for(int i=0;i<Math.max(labels.size(),values.size());i++){
   String left=i<labels.size()?labels.get(i):"",right=i<values.size()?values.get(i):"";
   lines.add(left+spaces(labelDots-width(left,layout),layout)+" "+right);
  }
 }
 private static void step(List<String> lines,String text,boolean checked,Layout layout){
  // The blank ideographic cell and tick have equal configured wide-glyph width.
  String box=checked?"[√]":"[　]";int boxDots=width(box,layout);
  int textDots=layout.printableDots-boxDots-layout.latinGlyphDots;
  if(textDots<layout.wideGlyphDots){
   lines.addAll(chunks(text,layout.printableDots,layout));lines.add(spaces(layout.printableDots-boxDots,layout)+box);return;
  }
  List<String> wrapped=chunks(text,textDots,layout);
  for(int i=0;i<wrapped.size();i++){
   String line=wrapped.get(i);lines.add(i==0?line+spaces(layout.printableDots-boxDots-width(line,layout),layout)+box:line);
  }
 }
 private static List<String> chunks(String text,int maxDots,Layout layout){
  List<String> lines=new ArrayList<>();
  StringBuilder line=new StringBuilder();int used=0;
  for(int offset=0;offset<text.length();){int cp=text.codePointAt(offset);offset+=Character.charCount(cp);
   int dots=cp<128?layout.latinGlyphDots:layout.wideGlyphDots;
   if(used+dots>maxDots&&line.length()>0){lines.add(line.toString());line.setLength(0);used=0;}
   line.appendCodePoint(cp);used+=dots;
  }
  if(line.length()>0||lines.isEmpty())lines.add(line.toString());return lines;
 }
}
