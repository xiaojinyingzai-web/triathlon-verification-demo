package com.triathlon.prep.core;

import java.util.*;

/** One immutable event/batch. Full document keys never leave the matching/storage implementation. */
public final class RaceRoster {
 public static final int MAX_ROWS=50000;
 /** Source annotation, not evidence that a pool test was completed. */
 public enum SwimTest { MARKED, EXPLICIT_BLANK, UNKNOWN }
 public static final class Athlete {
  public final String bib,name,group,team;
  public final SwimTest swimTest;
  private Athlete(String bib,String name,String group,String team,SwimTest swimTest){this.bib=bib;this.name=name;this.group=group;this.team=team;this.swimTest=swimTest;}
 }
 public static final class Entry {
  private final String identity;
  public final Athlete athlete;
  public Entry(String identity,String bib,String name,String group,String team){
   this(identity,bib,name,group,team,SwimTest.UNKNOWN);
  }
  public Entry(String identity,String bib,String name,String group,String team,SwimTest swimTest){
   this.identity=IdentityNumber.normalize(identity);
   if(swimTest==null)throw new IllegalArgumentException("Swimming annotation required");
   athlete=new Athlete(required(bib,80),required(name,128),required(group,128),optional(team,256),swimTest);
  }
  String identity(){return identity;}
 }
 public final String eventName,batchId;
 private final Map<String,Entry> entries;
 public RaceRoster(String eventName,String batchId,List<Entry> rows){
  this.eventName=required(eventName,128);
  if(batchId==null||!batchId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid batch ID");
  this.batchId=batchId;
  if(rows==null||rows.isEmpty()||rows.size()>MAX_ROWS)throw new IllegalArgumentException("Invalid roster size");
  Map<String,Entry> map=new LinkedHashMap<>();Set<String> bibs=new HashSet<>();
  for(Entry entry:rows){if(entry==null||map.put(entry.identity,entry)!=null||!bibs.add(entry.athlete.bib))throw new IllegalArgumentException("Duplicate roster key");}
  entries=Collections.unmodifiableMap(map);
 }
 public int size(){return entries.size();}
 public Athlete match(String document){Entry e=entries.get(IdentityNumber.normalize(document));return e==null?null:e.athlete;}
 List<Entry> storedEntries(){return new ArrayList<>(entries.values());}
 static String required(String value,int limit){String v=optional(value,limit);if(v.isEmpty())throw new IllegalArgumentException("Required roster field");return v;}
 static String optional(String value,int limit){String v=value==null?"":value.trim();if(v.length()>limit||v.matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("Invalid roster field");return v;}
}
