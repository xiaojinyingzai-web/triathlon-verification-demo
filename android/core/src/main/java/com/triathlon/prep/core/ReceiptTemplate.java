package com.triathlon.prep.core;
import java.util.*;
/** Immutable, finite template configuration. Roster values and semantic item IDs are not editable. */
public final class ReceiptTemplate {
 public enum TextKey { EVENT_HEADING,SUBTITLE,NAME_LABEL,TEAM_LABEL,GROUP_LABEL,BIB_LABEL,DATE_LABEL,DATE_VALUE,TIME_LABEL,TIME_VALUE,REMINDER,WISH }
 public enum Section { HEADING,SUBTITLE,FIELDS,BIB,STEPS,SCHEDULE,FOOTER }
 public enum FontSize { SMALL,NORMAL,LARGE }
 public enum Align { LEFT,CENTER,RIGHT }
 public enum Spacing { TIGHT,NORMAL,LOOSE }
 public static final class Style {
  public final FontSize size;public final boolean bold;public final Align align;public final Spacing spacing;
  public Style(FontSize size,boolean bold,Align align,Spacing spacing){if(size==null||align==null||spacing==null)throw new IllegalArgumentException("Complete finite style required");this.size=size;this.bold=bold;this.align=align;this.spacing=spacing;}
  @Override public boolean equals(Object o){if(!(o instanceof Style))return false;Style s=(Style)o;return size==s.size&&bold==s.bold&&align==s.align&&spacing==s.spacing;}
  @Override public int hashCode(){return Objects.hash(size,bold,align,spacing);}
 }
 public static final class ItemSpec {
  public final CheckInReceipt.Item id;public final String label;public final boolean visible;
  public ItemSpec(CheckInReceipt.Item id,String label,boolean visible){if(id==null)throw new IllegalArgumentException("Stable item ID required");this.id=id;this.label=validate(label,40,false);this.visible=visible;}
  @Override public boolean equals(Object o){if(!(o instanceof ItemSpec))return false;ItemSpec s=(ItemSpec)o;return id==s.id&&label.equals(s.label)&&visible==s.visible;}
  @Override public int hashCode(){return Objects.hash(id,label,visible);}
 }
 public final Map<TextKey,String> texts;public final List<ItemSpec> items;public final Map<Section,Style> styles;
 public ReceiptTemplate(Map<TextKey,String> texts,List<ItemSpec> items,Map<Section,Style> styles){
  if(texts==null||items==null||styles==null||texts.size()!=TextKey.values().length||styles.size()!=Section.values().length||items.size()!=CheckInReceipt.Item.values().length)throw new IllegalArgumentException("Complete template required");
  EnumMap<TextKey,String> copied=new EnumMap<>(TextKey.class);for(TextKey key:TextKey.values()){
   boolean optional=key==TextKey.EVENT_HEADING||key==TextKey.REMINDER||key==TextKey.WISH;
   copied.put(key,key==TextKey.EVENT_HEADING?CheckInReceipt.normalizeHeading(required(texts.get(key))):validate(texts.get(key),key==TextKey.REMINDER||key==TextKey.WISH?120:40,optional));
  }
  EnumSet<CheckInReceipt.Item> found=EnumSet.noneOf(CheckInReceipt.Item.class);List<ItemSpec> ordered=new ArrayList<>();for(ItemSpec item:items){if(item==null||!found.add(item.id))throw new IllegalArgumentException("Each stable item ID must occur once");ordered.add(item);}
  EnumMap<Section,Style> formats=new EnumMap<>(Section.class);for(Section section:Section.values()){Style style=styles.get(section);if(style==null)throw new IllegalArgumentException("Complete finite styles required");formats.put(section,style);}
  this.texts=Collections.unmodifiableMap(copied);this.items=Collections.unmodifiableList(ordered);this.styles=Collections.unmodifiableMap(formats);
 }
 private static String required(String text){if(text==null)throw new IllegalArgumentException("Text required");return text;}
 private static String validate(String value,int max,boolean optional){String text=required(value);for(int p=0;p<text.length();){int cp=text.codePointAt(p);p+=Character.charCount(cp);if(Character.isISOControl(cp)||cp==0x2028||cp==0x2029||cp>=0xd800&&cp<=0xdfff)throw new IllegalArgumentException("Template text cannot contain controls or invalid Unicode");}text=text.trim();if((!optional&&text.isEmpty())||text.codePointCount(0,text.length())>max)throw new IllegalArgumentException("Template text length invalid");return text;}
 public String text(TextKey key){return texts.get(Objects.requireNonNull(key));}
 public Style style(Section section){return styles.get(Objects.requireNonNull(section));}
 public ReceiptTemplate withText(TextKey key,String value){EnumMap<TextKey,String> next=new EnumMap<>(texts);next.put(Objects.requireNonNull(key),value);return new ReceiptTemplate(next,items,styles);}
 public ReceiptTemplate withItems(List<ItemSpec> value){return new ReceiptTemplate(texts,value,styles);}
 public ReceiptTemplate withStyle(Section section,Style value){EnumMap<Section,Style> next=new EnumMap<>(styles);next.put(Objects.requireNonNull(section),value);return new ReceiptTemplate(texts,items,next);}
 public static ReceiptTemplate defaults(){
  EnumMap<TextKey,String> text=new EnumMap<>(TextKey.class);String[] values={"","报道转单","姓名","所属团体","参赛组别","比赛号码","日期","10月17日","时间","07:30 - 09:00","","预祝顺利完赛"};int i=0;for(TextKey key:TextKey.values())text.put(key,values[i++]);
  List<ItemSpec> items=Arrays.asList(new ItemSpec(CheckInReceipt.Item.IDENTITY,"身份核验",true),new ItemSpec(CheckInReceipt.Item.DECLARATION,"签署声明书",true),new ItemSpec(CheckInReceipt.Item.SWIMMING,"深水测试",true),new ItemSpec(CheckInReceipt.Item.INSURANCE,"保险核验",true),new ItemSpec(CheckInReceipt.Item.BAG,"赛事包发放",true));
  EnumMap<Section,Style> styles=new EnumMap<>(Section.class);for(Section section:Section.values())styles.put(section,new Style(section==Section.BIB?FontSize.LARGE:FontSize.NORMAL,section!=Section.SCHEDULE,section==Section.FIELDS||section==Section.STEPS||section==Section.SCHEDULE?Align.LEFT:Align.CENTER,Spacing.NORMAL));return new ReceiptTemplate(text,items,styles);
 }
 @Override public boolean equals(Object o){if(!(o instanceof ReceiptTemplate))return false;ReceiptTemplate t=(ReceiptTemplate)o;return texts.equals(t.texts)&&items.equals(t.items)&&styles.equals(t.styles);}
 @Override public int hashCode(){return Objects.hash(texts,items,styles);}
}
