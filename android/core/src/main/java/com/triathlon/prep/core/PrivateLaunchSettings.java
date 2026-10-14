package com.triathlon.prep.core;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Separate private provisioning input. Public drafts never carry secrets or authorize execution. */
public final class PrivateLaunchSettings {
    public enum ReaderCredentialsPolicy { UNCONFIRMED, UNUSED_CONFIRMED, REQUIRED }
    public enum Confirm { EXECUTION, OFFLINE_READER, DEPENDENCIES, IMAGE_CONTRACT, THRESHOLDS, FACE_SERVICE, USB_NETWORK, USB_INITIALIZATION, USB_MANAGED_FALLBACK }
    public final ReaderCredentialsPolicy readerPolicy;
    private final String fingerprint,readerId,readerKey,readerSecret,faceId,faceKey,faceSecret;
    private final byte[] sam;
    private final EnumSet<Confirm> confirmations;

    public PrivateLaunchSettings(DeviceConfigDraft draft,ReaderCredentialsPolicy policy,
            String readerId,String readerKey,String readerSecret,String faceId,String faceKey,String faceSecret,
            byte[] sam,Set<Confirm> confirmations) {
        this(fingerprint(draft),policy,readerId,readerKey,readerSecret,faceId,faceKey,faceSecret,sam,confirmations);
    }
    private PrivateLaunchSettings(String fingerprint,ReaderCredentialsPolicy policy,
            String readerId,String readerKey,String readerSecret,String faceId,String faceKey,String faceSecret,
            byte[] sam,Set<Confirm> confirmations) {
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||policy==null||confirmations==null||sam!=null&&sam.length!=16)
            throw new IllegalArgumentException("Invalid private launch settings");
        this.fingerprint=fingerprint;readerPolicy=policy;
        this.readerId=bounded(readerId);this.readerKey=bounded(readerKey);this.readerSecret=bounded(readerSecret);
        this.faceId=bounded(faceId);this.faceKey=bounded(faceKey);this.faceSecret=bounded(faceSecret);
        this.sam=sam==null?null:sam.clone();this.confirmations=EnumSet.noneOf(Confirm.class);this.confirmations.addAll(confirmations);
    }
    private static String bounded(String value){if(value==null||value.length()>4096||value.matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("Invalid private credential value");return value;}
    public boolean matches(DeviceConfigDraft draft){return draft!=null&&fingerprint.equals(fingerprint(draft));}
    public boolean confirmed(Confirm flag){return confirmations.contains(flag);}
    public static Set<Confirm> requiredConfirmations(DeviceConfigDraft draft){
        EnumSet<Confirm> required=EnumSet.of(Confirm.EXECUTION,Confirm.DEPENDENCIES,Confirm.IMAGE_CONTRACT,Confirm.FACE_SERVICE);
        if(!draft.scoresOnly())required.add(Confirm.THRESHOLDS);
        if("USB".equals(draft.value(DeviceConfigDraft.Field.READER_TRANSPORT).trim()))required.addAll(EnumSet.of(Confirm.USB_NETWORK,Confirm.USB_INITIALIZATION,Confirm.USB_MANAGED_FALLBACK));
        else required.add(Confirm.OFFLINE_READER);
        return Collections.unmodifiableSet(required);
    }
    public String readerId(){return readerId;}public String readerKey(){return readerKey;}public String readerSecret(){return readerSecret;}
    public String faceId(){return faceId;}public String faceKey(){return faceKey;}public String faceSecret(){return faceSecret;}
    public byte[] samCode(){return sam==null?null:sam.clone();}
    /** Public, non-secret digest for operator-reviewed provisioning; includes every exact draft field. */
    public static String fingerprint(DeviceConfigDraft draft){
        if(draft==null)throw new IllegalArgumentException("Public draft required");
        try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            for(DeviceConfigDraft.Field f:DeviceConfigDraft.Field.values()){if(f==DeviceConfigDraft.Field.CHECKIN_PROFILE&&draft.value(f).isEmpty())continue;out.writeUTF(f.name());out.writeUTF(draft.value(f));}
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();
        }catch(Exception error){throw new IllegalArgumentException("Cannot bind public draft");}
    }
    byte[] encode()throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeInt(1);out.writeUTF(fingerprint);out.writeUTF(readerPolicy.name());
            for(String value:new String[]{readerId,readerKey,readerSecret,faceId,faceKey,faceSecret})out.writeUTF(value);
            out.writeBoolean(sam!=null);if(sam!=null)out.write(sam);
            out.writeInt(confirmations.size());for(Confirm flag:Confirm.values())if(confirmations.contains(flag))out.writeUTF(flag.name());
        }return bytes.toByteArray();
    }
    static PrivateLaunchSettings decode(byte[] plain)throws IOException{
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain))){
            if(in.readInt()!=1)throw new IOException("Unsupported private settings");String fingerprint=in.readUTF();ReaderCredentialsPolicy policy=ReaderCredentialsPolicy.valueOf(in.readUTF());
            String[] values=new String[6];for(int i=0;i<6;i++)values[i]=in.readUTF();byte[] sam=null;if(in.readBoolean()){sam=new byte[16];in.readFully(sam);}
            int count=in.readInt();if(count<0||count>Confirm.values().length)throw new IOException("Invalid private confirmations");EnumSet<Confirm> flags=EnumSet.noneOf(Confirm.class);
            for(int i=0;i<count;i++)if(!flags.add(Confirm.valueOf(in.readUTF())))throw new IOException("Duplicate private confirmation");
            if(in.read()!=-1)throw new IOException("Invalid private settings");return new PrivateLaunchSettings(fingerprint,policy,values[0],values[1],values[2],values[3],values[4],values[5],sam,flags);
        }catch(IllegalArgumentException error){throw new IOException("Invalid private settings");}
    }
    @Override public String toString(){return "PrivateLaunchSettings[redacted]";}
}
