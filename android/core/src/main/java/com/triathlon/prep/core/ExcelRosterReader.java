package com.triathlon.prep.core;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;
import org.apache.poi.hssf.usermodel.*;

/** Bounded read-only BIFF8 XLS / OOXML XLSX import. No formulas, macros or external links executed. */
public final class ExcelRosterReader {
 public enum Field { FILE, EVENT, GROUP, BIB, NAME, ID_NUMBER, TEAM, ROW, SWIMMING_TEST }
 public enum Code { UNSUPPORTED_FILE, UNREADABLE_FILE, MISSING_COLUMN, REQUIRED, INVALID_ID_LENGTH,
  INVALID_ID_DATE, INVALID_ID_CHECKSUM, LOST_PRECISION, DUPLICATE_ID, DUPLICATE_BIB,
  AMBIGUOUS_CONTEXT, EMPTY_FILE, TOO_LARGE, INVALID_CELL, OTHER }
 public static final class Problem {
  public final int sheet,row;public final Field field;public final Code code;
  Problem(int sheet,int row,Field field,Code code){this.sheet=sheet;this.row=row;this.field=field;this.code=code;}
 }
 public static final class Preview {
  public final String id,eventName;public final int totalRows,validRows,errorRows;public final List<Problem> errors;public final RaceRoster roster;
  Preview(String id,String event,int total,int invalid,List<Problem> errors,RaceRoster roster){this.id=id;eventName=event;totalRows=total;errorRows=invalid;validRows=total-invalid;this.errors=Collections.unmodifiableList(new ArrayList<>(errors));this.roster=roster;}
 }
 private static final int FILE_LIMIT=16*1024*1024,PART_LIMIT=16*1024*1024,TOTAL_XML_LIMIT=64*1024*1024;
 private static final class Cell {final String text;final boolean numeric,invalid;Cell(String text,boolean numeric,boolean invalid){this.text=text;this.numeric=numeric;this.invalid=invalid;}}
 private static final class Row {final int number;final Map<Integer,Cell> cells=new TreeMap<>();Row(int number){this.number=number;}}
 private static final class Sheet {final List<Row> rows=new ArrayList<>();}
 private ExcelRosterReader(){}
 static {
  // Workbook contents must never enter dependency diagnostics. Configure before POI loads.
  System.setProperty("log4j2.loggerContextFactory","org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
  System.setProperty("log4j2.simplelogLevel","OFF");
  System.setProperty("log4j2.statusLoggerLevel","OFF");
 }
 public static Preview read(InputStream source,String fileName,String eventName,String fallbackGroup) {
  String id=UUID.randomUUID().toString();List<Problem> errors=new ArrayList<>();String event=eventName==null?"":eventName.trim(),group=fallbackGroup==null?"":fallbackGroup.trim();
  try {
   if(source==null)throw new IOException("Missing file");
   byte[] data=bounded(source,FILE_LIMIT);String lower=fileName==null?"":fileName.toLowerCase(Locale.ROOT);
   List<Sheet> sheets;
   if(lower.endsWith(".xls")&&data.length>=8&&data[0]==(byte)0xd0&&data[1]==(byte)0xcf&&data[2]==0x11&&data[3]==(byte)0xe0)sheets=xls(data);
   else if(lower.endsWith(".xlsx")&&data.length>=4&&data[0]=='P'&&data[1]=='K')sheets=xlsx(data);
   else return failed(id,event,Code.UNSUPPORTED_FILE);
   List<RaceRoster.Entry> accepted=new ArrayList<>();Set<String> ids=new HashSet<>(),bibs=new HashSet<>();int total=0,invalid=0;
   for(int si=0;si<sheets.size();si++) {
    Sheet sheet=sheets.get(si);if(sheet.rows.isEmpty())continue;
    Map<Field,Integer> columns=null;int start=-1;
    for(int ri=0;ri<Math.min(50,sheet.rows.size());ri++){
     Map<Field,Integer> found=new EnumMap<>(Field.class);boolean duplicate=false;
     for(Map.Entry<Integer,Cell> c:sheet.rows.get(ri).cells.entrySet()) {Field f=header(c.getValue().text);if(f!=null&&found.put(f,c.getKey())!=null)duplicate=true;}
     if(found.containsKey(Field.ID_NUMBER)&&found.containsKey(Field.BIB)&&found.containsKey(Field.NAME)){
      if(duplicate){errors.add(new Problem(si+1,sheet.rows.get(ri).number,Field.ROW,Code.AMBIGUOUS_CONTEXT));break;}
      columns=found;start=ri+1;break;
     }
    }
    if(columns==null){errors.add(new Problem(si+1,0,Field.ROW,Code.MISSING_COLUMN));continue;}
    for(int ri=start;ri<sheet.rows.size();ri++){
     Row row=sheet.rows.get(ri);boolean empty=true;for(Integer c:columns.values()){Cell v=row.cells.get(c);if(v!=null&&!v.text.trim().isEmpty())empty=false;}if(empty)continue;
     if(++total>RaceRoster.MAX_ROWS)return failed(id,event,Code.TOO_LARGE);
     int before=errors.size();String bib=text(row,columns,Field.BIB,si,errors),name=text(row,columns,Field.NAME,si,errors),identity=text(row,columns,Field.ID_NUMBER,si,errors),team=text(row,columns,Field.TEAM,si,errors);
     String rowGroup=columns.containsKey(Field.GROUP)?text(row,columns,Field.GROUP,si,errors):group;
     String rowEvent=columns.containsKey(Field.EVENT)?text(row,columns,Field.EVENT,si,errors):event;
     if(event.isEmpty()&&!rowEvent.isEmpty())event=rowEvent;
     if(rowEvent.isEmpty())errors.add(new Problem(si+1,row.number,Field.EVENT,Code.REQUIRED));
     else if(!event.equals(rowEvent))errors.add(new Problem(si+1,row.number,Field.EVENT,Code.AMBIGUOUS_CONTEXT));
     for(Field f:new Field[]{Field.BIB,Field.NAME,Field.ID_NUMBER,Field.GROUP}){
      String v=f==Field.BIB?bib:f==Field.NAME?name:f==Field.ID_NUMBER?identity:rowGroup;
      if(v.isEmpty())errors.add(new Problem(si+1,row.number,f,Code.REQUIRED));
     }
     String normalized=null;
     Cell sourceId=row.cells.get(columns.get(Field.ID_NUMBER));
     if(sourceId!=null&&(sourceId.numeric||identity.matches("[0-9]+(?:\\.[0-9]+)?[eE][+-]?[0-9]+")))errors.add(new Problem(si+1,row.number,Field.ID_NUMBER,Code.LOST_PRECISION));
     else if(!identity.isEmpty())try{normalized=IdentityNumber.normalize(identity);}catch(IdentityNumber.Invalid ex){errors.add(new Problem(si+1,row.number,Field.ID_NUMBER,Code.valueOf(ex.reason.name())));}
     if(normalized!=null&&!ids.add(normalized))errors.add(new Problem(si+1,row.number,Field.ID_NUMBER,Code.DUPLICATE_ID));
     if(!bib.isEmpty()&&!bibs.add(bib))errors.add(new Problem(si+1,row.number,Field.BIB,Code.DUPLICATE_BIB));
     if(errors.size()==before)try{accepted.add(new RaceRoster.Entry(normalized,bib,name,rowGroup,team,swimming(row,columns)));RaceRoster.required(event,128);}catch(IllegalArgumentException ex){errors.add(new Problem(si+1,row.number,Field.ROW,Code.INVALID_CELL));}
     if(errors.size()!=before)invalid++;
    }
   }
   if(total==0)errors.add(new Problem(0,0,Field.FILE,Code.EMPTY_FILE));
   RaceRoster roster=errors.isEmpty()?new RaceRoster(event,id,accepted):null;
   return new Preview(id,event,total,invalid,errors,roster);
  }catch(Limit ex){return failed(id,event,Code.TOO_LARGE);}
  catch(Exception | LinkageError ex){return failed(id,event,Code.UNREADABLE_FILE);}
 }
 private static Preview failed(String id,String event,Code code){return new Preview(id,event,0,0,Arrays.asList(new Problem(0,0,Field.FILE,code)),null);}
 private static String text(Row row,Map<Field,Integer> columns,Field field,int sheet,List<Problem> errors){
  Integer index=columns.get(field);Cell cell=index==null?null:row.cells.get(index);if(cell==null)return "";
  if(cell.invalid){errors.add(new Problem(sheet+1,row.number,field,Code.INVALID_CELL));return "";}return cell.text.trim();
 }
 private static Field header(String raw){
  String s=raw==null?"":raw.trim();
  if(Arrays.asList("出发号","比赛号码","参赛号","参赛号码","比赛号").contains(s))return Field.BIB;
  if(Arrays.asList("姓名","选手姓名").contains(s))return Field.NAME;
  if(Arrays.asList("证件号码","身份证号","身份证号码","证件号").contains(s))return Field.ID_NUMBER;
  if(Arrays.asList("参赛组别","组别","比赛组别").contains(s))return Field.GROUP;
  if(Arrays.asList("所属团体会员","所属团体","团体","所属单位","团队").contains(s))return Field.TEAM;
  if(s.equals("游泳测试"))return Field.SWIMMING_TEST;
  if(Arrays.asList("赛事名称","赛事").contains(s))return Field.EVENT;return null;
 }
 private static RaceRoster.SwimTest swimming(Row row,Map<Field,Integer> columns){
  Integer index=columns.get(Field.SWIMMING_TEST);
  if(index==null)return RaceRoster.SwimTest.UNKNOWN;
  Cell cell=row.cells.get(index);
  if(cell==null)return RaceRoster.SwimTest.EXPLICIT_BLANK;
  // Formulas, numbers and unconfirmed text have no established annotation meaning.
  if(cell.invalid)return RaceRoster.SwimTest.UNKNOWN;
  String value=cell.text.trim();
  if(value.isEmpty())return RaceRoster.SwimTest.EXPLICIT_BLANK;
  if(cell.numeric)return RaceRoster.SwimTest.UNKNOWN;
  if(Arrays.asList("√","✓","✔").contains(value))return RaceRoster.SwimTest.MARKED;
  return RaceRoster.SwimTest.UNKNOWN;
 }
 private static List<Sheet> xls(byte[] data)throws IOException{
  List<Sheet> sheets=new ArrayList<>();
  try(HSSFWorkbook book=new HSSFWorkbook(new ByteArrayInputStream(data))){
   if(book.getNumberOfSheets()>32)throw new Limit();int count=0;
   for(int s=0;s<book.getNumberOfSheets();s++){
    Sheet target=new Sheet();for(org.apache.poi.ss.usermodel.Row source:book.getSheetAt(s)){
     if(++count>RaceRoster.MAX_ROWS+1600)throw new Limit();Row row=new Row(source.getRowNum()+1);
     for(org.apache.poi.ss.usermodel.Cell cell:source){if(cell.getColumnIndex()>127)continue;String v="";boolean number=false,invalid=false;
      switch(cell.getCellType()){
       case STRING:v=cell.getStringCellValue();break;
       case NUMERIC:number=true;double n=cell.getNumericCellValue();if(!Double.isFinite(n)||n!=Math.rint(n)||Math.abs(n)>9007199254740991d)invalid=true;else {v=Long.toString((long)n);String fmt=cell.getCellStyle().getDataFormatString();if(fmt!=null&&fmt.matches("0{2,20}")&&n>=0)v=String.format(Locale.ROOT,"%0"+fmt.length()+"d",(long)n);}break;
       case BLANK:break;
       default:invalid=true;break;
      }
      if(v.length()>1024)throw new Limit();row.cells.put(cell.getColumnIndex(),new Cell(v,number,invalid));
     }
     target.rows.add(row);
    }sheets.add(target);
   }
  }return sheets;
 }
 private static List<Sheet> xlsx(byte[] data)throws Exception{
  Map<String,byte[]> parts=new HashMap<>();int total=0,count=0;
  try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
   if(++count>256)throw new Limit();if(entry.isDirectory())continue;String name=entry.getName();
   if(name.startsWith("/")||name.contains("..")||parts.containsKey(name))throw new IOException("Invalid workbook part");
   byte[] part=bounded(zip,PART_LIMIT);total+=part.length;if(total>TOTAL_XML_LIMIT)throw new Limit();
   if(name.startsWith("xl/"))parts.put(name,part);
  }}
  List<String> sheetIds=new ArrayList<>();parse(parts.get("xl/workbook.xml"),new SafeXml(){public void startElement(String uri,String local,String q,Attributes a)throws SAXException{if(tag(local,q).equals("sheet")){String id=a.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");if(id==null)id=a.getValue("r:id");sheetIds.add(id);}}});
  Map<String,String> relations=new HashMap<>();parse(parts.get("xl/_rels/workbook.xml.rels"),new SafeXml(){public void startElement(String uri,String local,String q,Attributes a)throws SAXException{if(tag(local,q).equals("Relationship")){String target=a.getValue("Target");if("External".equals(a.getValue("TargetMode")))return;String normalized=URI.create("xl/").resolve(target).normalize().getPath();if(normalized.startsWith("/"))normalized=normalized.substring(1);relations.put(a.getValue("Id"),normalized);}}});
  List<String> strings=new ArrayList<>();if(parts.containsKey("xl/sharedStrings.xml"))parse(parts.get("xl/sharedStrings.xml"),new SafeXml(){StringBuilder value;boolean text,phonetic;public void startElement(String uri,String local,String q,Attributes a)throws SAXException{String t=tag(local,q);if(t.equals("si"))value=new StringBuilder();if(t.equals("rPh"))phonetic=true;if(t.equals("t"))text=true;}public void characters(char[] ch,int start,int len)throws SAXException{if(text&&!phonetic&&value!=null){value.append(ch,start,len);if(value.length()>1024)throw new SAXException("Limit");}}public void endElement(String uri,String local,String q)throws SAXException{String t=tag(local,q);if(t.equals("t"))text=false;if(t.equals("rPh"))phonetic=false;if(t.equals("si")){if(strings.size()>RaceRoster.MAX_ROWS*8)throw new SAXException("Limit");strings.add(value.toString());value=null;}}});
  if(sheetIds.size()>32)throw new Limit();List<Sheet> sheets=new ArrayList<>();
  for(String id:sheetIds){String path=relations.get(id);if(path==null||!path.startsWith("xl/worksheets/"))throw new IOException("Missing worksheet");Sheet sheet=new Sheet();
   parse(parts.get(path),new SafeXml(){Row row;String type;StringBuilder value;boolean inValue,formula;int column;
    public void startElement(String uri,String local,String q,Attributes a)throws SAXException{String t=tag(local,q);
     if(t.equals("row")){int n=a.getValue("r")==null?sheet.rows.size()+1:Integer.parseInt(a.getValue("r"));if(n<1||n>1048576||sheet.rows.size()>RaceRoster.MAX_ROWS+50)throw new SAXException("Limit");row=new Row(n);}
     if(t.equals("c")){if(row==null)throw new SAXException("Missing row");String ref=a.getValue("r");if(ref==null||!ref.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))throw new SAXException("Invalid reference");column=0;for(char c:ref.toCharArray()){if(c<'A'||c>'Z')break;column=column*26+c-'A'+1;}column--;if(column>127)throw new SAXException("Limit");type=a.getValue("t");value=new StringBuilder();formula=false;}
     if(t.equals("f"))formula=true;if(t.equals("v")||t.equals("t"))inValue=true;
    }
    public void characters(char[] ch,int start,int len)throws SAXException{if(inValue&&value!=null){value.append(ch,start,len);if(value.length()>1024)throw new SAXException("Limit");}}
    public void endElement(String uri,String local,String q)throws SAXException{String t=tag(local,q);if(t.equals("v")||t.equals("t"))inValue=false;
     if(t.equals("c")){String v=value.toString();boolean numeric=type==null||type.equals("n"),invalid=formula;
      if("s".equals(type)){int i=Integer.parseInt(v);if(i<0||i>=strings.size())throw new SAXException("Invalid shared string");v=strings.get(i);}
      else if(numeric&&!v.isEmpty()){try{java.math.BigDecimal n=new java.math.BigDecimal(v);v=n.toBigIntegerExact().toString();if(n.abs().compareTo(new java.math.BigDecimal("9007199254740991"))>0)invalid=true;}catch(Exception ex){invalid=true;}}
      else if(!numeric&&!"inlineStr".equals(type)&&!"str".equals(type))invalid=true;
      if(row.cells.put(column,new Cell(v,numeric,invalid))!=null)throw new SAXException("Duplicate cell");value=null;
     }if(t.equals("row")){sheet.rows.add(row);row=null;}
    }
   });sheets.add(sheet);
  }return sheets;
 }
 private static String tag(String local,String q){return local.isEmpty()?q:local;}
 private static class SafeXml extends DefaultHandler2 {
  @Override public void startDTD(String name,String publicId,String systemId)throws SAXException{throw new SAXException("DTD is not allowed");}
  @Override public InputSource resolveEntity(String publicId,String systemId)throws SAXException{throw new SAXException("External entity is not allowed");}
 }
 private static void parse(byte[] bytes,SafeXml handler)throws Exception{
  if(bytes==null)throw new IOException("Missing XML part");SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
  XMLReader reader=factory.newSAXParser().getXMLReader();reader.setContentHandler(handler);reader.setEntityResolver(handler);
  reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler);reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
 }
 private static final class Limit extends IOException {}
 private static byte[] bounded(InputStream input,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int n,total=0;while((n=input.read(block))!=-1){total+=n;if(total>limit)throw new Limit();out.write(block,0,n);}return out.toByteArray();}
}
