package com.triathlon.prep.core;

import java.io.*;

/** One owner for staged candidates. Caller serializes UI/file work; only confirmed valid batches commit. */
public final class RosterImportSession {
 private final FileRosterStore store;
 private ExcelRosterReader.Preview candidate;
 public RosterImportSession(FileRosterStore store){if(store==null)throw new IllegalArgumentException("Roster store required");this.store=store;}
 public ExcelRosterReader.Preview preview(InputStream source,String fileName,String event,String fallbackGroup){return preview(source,fileName,event,fallbackGroup,ExcelRosterReader.WorksheetScope.ALL_WORKSHEETS);}
 public ExcelRosterReader.Preview preview(InputStream source,String fileName,String event,String fallbackGroup,ExcelRosterReader.WorksheetScope scope){candidate=ExcelRosterReader.read(source,fileName,event,fallbackGroup,scope);return candidate;}
 public ExcelRosterReader.Preview candidate(){return candidate;}
 public void discard(String id){if(candidate!=null&&candidate.id.equals(id))candidate=null;}
 public RaceRoster confirm(String id,boolean replaceAllowed)throws IOException{
  if(!replaceAllowed||candidate==null||!candidate.id.equals(id)||candidate.roster==null)throw new IllegalStateException("Candidate is unavailable or replacement is blocked");
  RaceRoster result=candidate.roster;store.replace(result);candidate=null;return result;
 }
}
