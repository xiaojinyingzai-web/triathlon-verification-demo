package com.triathlon.prep;
import android.content.Context;import com.triathlon.prep.core.*;
import java.io.*;import java.nio.charset.*;import java.nio.channels.*;import java.util.*;import org.json.*;
/** Debug private inbox for public configuration. No runtime, secret store, SDK or camera calls. */
final class PublicConfigMaintenanceController {
 static final int MAX_INPUT=65536;
 private interface Work{String run()throws Exception;}
 private static File directory(Context app)throws IOException{File dir=new File(app.getNoBackupFilesDir().getCanonicalFile(),"public-config-maintenance");if(!dir.getCanonicalFile().equals(dir.getAbsoluteFile())||(!dir.isDirectory()&&!dir.mkdirs()))throw new IOException("Public inbox unavailable");return dir;}
 private static synchronized String exclusive(Context app,Work work)throws Exception{
  File lockPath=new File(directory(app),"command.lock");if(!lockPath.getCanonicalFile().equals(lockPath.getAbsoluteFile()))throw new IOException("Public lock invalid");
  try(RandomAccessFile lockFile=new RandomAccessFile(lockPath,"rw");FileChannel channel=lockFile.getChannel();FileLock lock=channel.tryLock()){if(lock==null)throw new IOException("Public maintenance busy");return work.run();}
 }
 private static PublicConfigMaintenance api(Context app)throws IOException{
  File dir=new File(app.getFilesDir().getCanonicalFile(),"device-config");if(!dir.getCanonicalFile().equals(dir.getAbsoluteFile()))throw new IOException("Public store path invalid");
  for(String name:new String[]{"device-config.bin","device-config.bin.new","device-config.bin.bak"}){File file=new File(dir,name);if(!file.getCanonicalFile().equals(file.getAbsoluteFile()))throw new IOException("Public snapshot path invalid");}
  return new PublicConfigMaintenance(new FileDeviceConfigStore(dir));
 }
 private static String summary(FileDeviceConfigStore.Snapshot snapshot)throws JSONException{return new JSONObject().put("revision",snapshot.revision).put("missing",new JSONArray(snapshot.current.review().missing)).toString();}
 static String read(Context app)throws Exception{return exclusive(app,()->summary(api(app).read()));}
 private static byte[] consume(Context app)throws IOException{
  File input=new File(directory(app),"request.json");if(!input.getCanonicalFile().equals(input.getAbsoluteFile())||!input.isFile())throw new IOException("Public request missing or invalid");
  try(InputStream stream=new FileInputStream(input)){if(!input.delete())throw new IOException("Public request not one-use");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];try{int n;while((n=stream.read(block))!=-1){if(n==0){int b=stream.read();if(b<0)break;block[0]=(byte)b;n=1;}if(bytes.size()>MAX_INPUT-n)throw new IOException("Public request capacity exceeded");bytes.write(block,0,n);}if(bytes.size()==0)throw new IOException("Empty public request");return bytes.toByteArray();}finally{Arrays.fill(block,(byte)0);}}
 }
 private static long integer(JSONObject object,String key)throws JSONException{Object raw=object.get(key);if(!(raw instanceof Integer||raw instanceof Long))throw new JSONException("Integer required");return ((Number)raw).longValue();}
 private static JSONObject object(JSONTokener parser,int depth)throws Exception{
  if(depth>1||parser.nextClean()!='{')throw new IOException("Public object nesting invalid");JSONObject result=new JSONObject();Set<String> seen=new HashSet<>();char first=parser.nextClean();if(first=='}')return result;parser.back();
  for(;;){
   if(parser.nextClean()!='"')throw new IOException("Quoted public key required");parser.back();Object keyValue=parser.nextValue();if(!(keyValue instanceof String)||!seen.add((String)keyValue)||parser.nextClean()!=':')throw new IOException("Duplicate or invalid public key");
   char start=parser.nextClean();parser.back();Object value;
   if(start=='{')value=object(parser,depth+1);
   else if(start=='"')value=parser.nextValue();
   else if(start=='-'||start>='0'&&start<='9'){value=parser.nextValue();if(!(value instanceof Number))throw new IOException("Public integer required");}
   else throw new IOException("Public value type invalid");
   result.put((String)keyValue,value);char delimiter=parser.nextClean();if(delimiter=='}')return result;if(delimiter!=',')throw new IOException("Public delimiter invalid");
  }
 }
 static String apply(Context app)throws Exception{return exclusive(app,()->{
  byte[] bytes=consume(app);try{
   String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();JSONTokener parser=new JSONTokener(text);JSONObject object=object(parser,0);if(parser.nextClean()!=0)throw new IOException("Public trailing content invalid");
   Set<String> keys=new HashSet<>();Iterator<String> it=object.keys();while(it.hasNext())keys.add(it.next());if(!keys.equals(new HashSet<>(Arrays.asList("schema","expectedRevision","fields")))||integer(object,"schema")!=1)throw new IOException("Public request schema invalid");Object raw=object.get("fields");if(!(raw instanceof JSONObject))throw new IOException("Public fields required");JSONObject fields=(JSONObject)raw;Map<String,String> patch=new LinkedHashMap<>();Iterator<String> names=fields.keys();while(names.hasNext()){String key=names.next();Object field=fields.get(key);if(!(field instanceof String))throw new IOException("Public string value required");patch.put(key,(String)field);}
   return summary(api(app).apply(patch,integer(object,"expectedRevision")));
  }finally{Arrays.fill(bytes,(byte)0);}
 });}
}
