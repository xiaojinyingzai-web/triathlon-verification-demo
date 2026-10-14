package com.triathlon.prep.core;

import java.io.*;import java.nio.file.*;import java.util.Arrays;import javax.crypto.*;import javax.crypto.spec.GCMParameterSpec;

/** App-private authenticated snapshot. No fallback to old authorizations after tamper or key loss. */
public final class EncryptedLaunchStore {
    private static final byte[] HEADER={0x50,0x4c,0x53,1};
    private static final int LIMIT=160000;
    public enum Status { MISSING, READY, KEY_UNAVAILABLE, INVALID, IO_FAILURE }
    public interface KeyProvider { SecretKey get(boolean forWrite)throws Exception; }
    public static final class Load {
        public final Status status;public final PrivateLaunchSettings settings;
        private Load(Status status,PrivateLaunchSettings settings){this.status=status;this.settings=settings;}
        @Override public String toString(){return "PrivateLaunchLoad["+status+"]";}
    }
    private final File directory,file,pending;private final KeyProvider keys;
    public EncryptedLaunchStore(File directory,KeyProvider keys){
        if(directory==null||keys==null)throw new IllegalArgumentException("Private store required");
        this.directory=directory;this.file=new File(directory,"private-launch.bin");pending=new File(directory,"private-launch.bin.new");this.keys=keys;
    }
    public synchronized Load load(){
        if(!file.exists())return new Load(Status.MISSING,null);
        byte[] encoded;
        try{if(!file.isFile()||Files.isSymbolicLink(file.toPath())||file.length()<33||file.length()>LIMIT)return new Load(Status.INVALID,null);encoded=Files.readAllBytes(file.toPath());}
        catch(IOException|SecurityException error){return new Load(Status.IO_FAILURE,null);}
        SecretKey key;try{key=keys.get(false);if(key==null)return new Load(Status.KEY_UNAVAILABLE,null);}catch(Exception error){return new Load(Status.KEY_UNAVAILABLE,null);}
        byte[] plain=null;
        try{
            if(encoded.length<33||encoded.length>LIMIT||!Arrays.equals(HEADER,Arrays.copyOf(encoded,4)))return new Load(Status.INVALID,null);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(encoded,4,16)));cipher.updateAAD(HEADER);
            plain=cipher.doFinal(encoded,16,encoded.length-16);return new Load(Status.READY,PrivateLaunchSettings.decode(plain));
        }catch(Exception error){return new Load(Status.INVALID,null);}finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    /** Trusted provisioning only; never invoked by public editor or startup. Creates key only on explicit save. */
    public synchronized void save(PrivateLaunchSettings settings)throws IOException{
        if(settings==null)throw new IOException("Private settings required");byte[] plain=null;
        try{
            if(!directory.exists()&&!directory.mkdirs()||!directory.isDirectory()||Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(file.toPath())||Files.isSymbolicLink(pending.toPath()))throw new IOException();
            SecretKey key=keys.get(true);if(key==null)throw new IOException();plain=settings.encode();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(HEADER);
            byte[] encrypted=cipher.doFinal(plain),iv=cipher.getIV();if(iv.length!=12||encrypted.length+16>LIMIT)throw new IOException();
            try(FileOutputStream stream=new FileOutputStream(pending)){stream.write(HEADER);stream.write(iv);stream.write(encrypted);stream.getFD().sync();}
            Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception error){throw new IOException("Private launch settings save failed");}finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
}
