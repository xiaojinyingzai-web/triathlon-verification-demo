package com.triathlon.prep.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** App-private single-owner atomic snapshot. Original workbook is not retained. */
public final class FileRosterStore implements Verification.RosterProvider {
 private static final int MAGIC=0x52535431;
 private final File file,pending,backup;
 public FileRosterStore(File directory)throws IOException{
  if(directory==null||(!directory.exists()&&!directory.mkdirs())||!directory.isDirectory())throw new IOException("Private roster directory unavailable");
  file=new File(directory,"roster.bin");pending=new File(directory,"roster.bin.new");backup=new File(directory,"roster.bin.bak");
 }
 @Override public synchronized RaceRoster current()throws IOException{
  recover();if(!file.exists())return null;
  if(!file.isFile()||file.length()>32*1024*1024)throw new IOException("Invalid roster snapshot");
  CRC32 crc=new CRC32();
  try(DataInputStream in=new DataInputStream(new CheckedInputStream(new FileInputStream(file),crc))){
   if(in.readInt()!=MAGIC)throw new IOException("Unknown roster format");
   int version=in.readInt();if(version!=1&&version!=2)throw new IOException("Unknown roster format");
   String event=in.readUTF(),batch=in.readUTF();int count=in.readInt();
   if(count<1||count>RaceRoster.MAX_ROWS)throw new IOException("Invalid roster count");
   List<RaceRoster.Entry> rows=new ArrayList<>();
   for(int i=0;i<count;i++){
    String identity=in.readUTF(),bib=in.readUTF(),name=in.readUTF(),group=in.readUTF(),team=in.readUTF();
    RaceRoster.SwimTest swim=version==1?RaceRoster.SwimTest.UNKNOWN:RaceRoster.SwimTest.valueOf(in.readUTF());
    rows.add(new RaceRoster.Entry(identity,bib,name,group,team,swim));
   }
   long actual=crc.getValue();if(in.readLong()!=actual||in.read()!=-1)throw new IOException("Corrupt roster snapshot");
   return new RaceRoster(event,batch,rows);
  }catch(IllegalArgumentException ex){throw new IOException("Invalid roster snapshot");}
 }
 public synchronized void replace(RaceRoster roster)throws IOException{
  if(roster==null)throw new IOException("Validated roster required");recover();
  CRC32 crc=new CRC32();
  try(FileOutputStream stream=new FileOutputStream(pending);DataOutputStream out=new DataOutputStream(new CheckedOutputStream(stream,crc))){
   out.writeInt(MAGIC);out.writeInt(2);out.writeUTF(roster.eventName);out.writeUTF(roster.batchId);out.writeInt(roster.size());
   for(RaceRoster.Entry e:roster.storedEntries()){out.writeUTF(e.identity());out.writeUTF(e.athlete.bib);out.writeUTF(e.athlete.name);out.writeUTF(e.athlete.group);out.writeUTF(e.athlete.team);out.writeUTF(e.athlete.swimTest.name());}
   out.writeLong(crc.getValue());out.flush();stream.getFD().sync();
  }
  if(file.exists()&&!file.renameTo(backup))throw new IOException("Cannot preserve current roster");
  if(!pending.renameTo(file)){if(backup.exists()&&!backup.renameTo(file))throw new IOException("Roster replace/restore failed");throw new IOException("Roster replace failed");}
  if(backup.exists()&&!backup.delete())throw new IOException("Roster backup cleanup failed");
 }
 private void recover()throws IOException{
  if(backup.exists()){if(file.exists()&&!file.delete())throw new IOException("Cannot recover roster");if(!backup.renameTo(file))throw new IOException("Cannot restore roster");}
  if(pending.exists()&&!pending.delete())throw new IOException("Cannot discard interrupted roster write");
 }
}
