package com.triathlon.prep.nextday.host;
import android.util.AtomicFile;
import java.io.*;

/** One exact Host-owned profile; never deletes anything outside its AtomicFile paths. */
public final class AtomicProfilePacketStore implements AuthenticatedProfileInstaller.Store {
 private final AtomicFile file;
 public AtomicProfilePacketStore(File path)throws IOException{
  if(path==null||!path.getCanonicalFile().equals(path.getAbsoluteFile()))throw new IOException("Profile path invalid");
  File parent=path.getAbsoluteFile().getParentFile();
  if(parent==null||(!parent.isDirectory()&&!parent.mkdirs())||!parent.isDirectory())throw new IOException("Profile folder unavailable");
  file=new AtomicFile(path);
 }
 public synchronized byte[] read()throws IOException{
  try(InputStream input=file.openRead()){
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int count;
   while((count=input.read(block))!=-1){if(bytes.size()+count>AuthenticatedProfileInstaller.MAX_PACKET)throw new IOException("Profile packet capacity exceeded");bytes.write(block,0,count);}
   return bytes.toByteArray();
  }catch(FileNotFoundException missing){
   File base=file.getBaseFile(),parent=base.getAbsoluteFile().getParentFile();
   if(base.exists()||new File(base.getPath()+".bak").exists()||new File(base.getPath()+".new").exists()||parent==null||!parent.isDirectory())throw new IOException("Profile recovery unavailable",missing);
   String[] names=parent.list();if(names==null)throw new IOException("Profile absence unconfirmed",missing);
   for(String name:names)if(name.equals(base.getName())||name.equals(base.getName()+".bak")||name.equals(base.getName()+".new"))throw new IOException("Profile recovery unavailable",missing);
   return null;
  }
 }
 public synchronized void replace(byte[] packet)throws IOException{
  if(packet==null){file.delete();if(read()!=null)throw new IOException("Profile absence restoration failed");return;}
  if(packet.length>AuthenticatedProfileInstaller.MAX_PACKET)throw new IOException("Profile capacity exceeded");
  FileOutputStream output=null;
  try{output=file.startWrite();output.write(packet);file.finishWrite(output);output=null;}
  finally{if(output!=null)file.failWrite(output);}
 }
}
