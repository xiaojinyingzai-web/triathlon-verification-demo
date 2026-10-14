package com.triathlon.prep;

import android.content.Context;import com.triathlon.prep.core.*;import java.io.*;import java.nio.charset.*;import java.util.*;import org.json.*;

/** Trusted local maintenance only. Secrets arrive in a one-use app-private inbox, never intent extras. */
public final class PrivateProvisioningController {
    public enum Outcome { SAVED, MISSING_INPUT, INVALID_INPUT, STALE_PUBLIC, SAVE_FAILED }
    private static final int MAX_BYTES=160000;
    private static final Set<String> FIELDS=new HashSet<>(Arrays.asList("version","publicFingerprint","readerPolicy","readerAppId","readerAppKey","readerAppSecret","faceAppId","faceAppKey","faceAppSecret","samCodeHex","confirmations"));
    private PrivateProvisioningController(){}
    public static File inbox(Context app){return new File(new File(app.getNoBackupFilesDir(),"private-provision"),"request.json");}
    private static DeviceConfigDraft current(Context app)throws IOException{return new FileDeviceConfigStore(new File(app.getFilesDir(),"device-config")).load().current;}
    public static String describe(Context app)throws IOException{
        DeviceConfigDraft draft=current(app);if(!draft.review().parametersComplete)throw new IOException("Public parameters incomplete");
        try{return new JSONObject().put("version",1).put("publicFingerprint",PrivateLaunchSettings.fingerprint(draft)).put("publicParameters",new JSONObject(draft.toMap())).toString();}
        catch(JSONException error){throw new IOException("Public description unavailable");}
    }
    public static Outcome consume(Context app){
        File input=inbox(app);if(!input.exists())return Outcome.MISSING_INPUT;
        byte[] bytes=null;boolean ownedInput=false;
        try{
            File expected=new File(new File(app.getNoBackupFilesDir().getCanonicalFile(),"private-provision"),"request.json");
            if(!input.getCanonicalFile().equals(expected))return Outcome.INVALID_INPUT;
            ownedInput=true;
            if(!input.isFile()||input.length()<2||input.length()>MAX_BYTES)return Outcome.INVALID_INPUT;
            try(FileInputStream stream=new FileInputStream(input);ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
                // Unlink before parsing: failed submissions do not leave a reusable plaintext request.
                if(!input.delete())return Outcome.INVALID_INPUT;
                byte[] chunk=new byte[4096];int count;while((count=stream.read(chunk))!=-1){if(buffer.size()>MAX_BYTES-count)return Outcome.INVALID_INPUT;buffer.write(chunk,0,count);}Arrays.fill(chunk,(byte)0);bytes=buffer.toByteArray();
            }
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JSONObject data=new JSONObject(text);Set<String> actual=new HashSet<>();Iterator<String> names=data.keys();while(names.hasNext())actual.add(names.next());
            if(!actual.equals(FIELDS)||!(data.get("version") instanceof Number)||((Number)data.get("version")).doubleValue()!=1)return Outcome.INVALID_INPUT;
            DeviceConfigDraft draft=current(app);if(!draft.review().parametersComplete)return Outcome.INVALID_INPUT;
            if(!PrivateLaunchSettings.fingerprint(draft).equals(string(data,"publicFingerprint")))return Outcome.STALE_PUBLIC;
            Object flags=data.get("confirmations");if(!(flags instanceof JSONArray))return Outcome.INVALID_INPUT;
            EnumSet<PrivateLaunchSettings.Confirm> confirmed=EnumSet.noneOf(PrivateLaunchSettings.Confirm.class);JSONArray array=(JSONArray)flags;
            for(int i=0;i<array.length();i++){if(!(array.get(i) instanceof String)||!confirmed.add(PrivateLaunchSettings.Confirm.valueOf((String)array.get(i))))return Outcome.INVALID_INPUT;}
            String hex=string(data,"samCodeHex");byte[] sam=null;if(!hex.isEmpty()){if(!hex.matches("[0-9a-fA-F]{32}"))return Outcome.INVALID_INPUT;sam=new byte[16];for(int i=0;i<16;i++)sam[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);}
            PrivateLaunchSettings settings=new PrivateLaunchSettings(draft,PrivateLaunchSettings.ReaderCredentialsPolicy.valueOf(string(data,"readerPolicy")),
                    string(data,"readerAppId"),string(data,"readerAppKey"),string(data,"readerAppSecret"),string(data,"faceAppId"),string(data,"faceAppKey"),string(data,"faceAppSecret"),sam,confirmed);
            if(!LiveProductionConfigLoader.map(draft,settings).ready())return Outcome.INVALID_INPUT;
            try{AndroidLaunchStore.saveConfirmed(app,settings);return Outcome.SAVED;}catch(IOException failure){return Outcome.SAVE_FAILED;}
        }catch(Exception error){return Outcome.INVALID_INPUT;}finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);if(ownedInput&&input.exists()&&input.isFile())input.delete();}
    }
    private static String string(JSONObject data,String field)throws JSONException{Object value=data.get(field);if(!(value instanceof String))throw new JSONException("String field required");return (String)value;}
}
