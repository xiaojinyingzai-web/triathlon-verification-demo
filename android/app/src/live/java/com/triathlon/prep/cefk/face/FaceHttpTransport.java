package com.triathlon.prep.cefk.face;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Disabled until explicit REAL configuration; never instantiated by simulation transport. */
public final class FaceHttpTransport implements FaceClient.Transport {
    public interface JsonCodec {
        String encode(Map<String,Object> object) throws Exception;
        Map<String,Object> decode(String json) throws Exception;
    }
    private final FaceClient.Config config;
    private final JsonCodec codec;
    private final int maxResponseBytes;
    public FaceHttpTransport(FaceClient.Config config,JsonCodec codec,int maxResponseBytes) {
        this.config=config;this.codec=codec;this.maxResponseBytes=maxResponseBytes;
    }
    public FaceClient.Mode mode(){return FaceClient.Mode.REAL;}
    public Map<String,Object> post(Map<String,Object> request,int timeout,FaceClient.Cancellation cancel) throws Exception {
        config.check();if(config.mode!=FaceClient.Mode.REAL||codec==null||maxResponseBytes<=0)throw new FaceClient.Failure(FaceClient.Error.UNAVAILABLE);
        cancel.check();
        HttpURLConnection connection=(HttpURLConnection)new URL(config.endpoint).openConnection();
        FaceClient.Cancellation.Registration abort=cancel.onCancel(connection::disconnect);
        byte[] body=null;
        try {
        cancel.check();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(timeout);connection.setReadTimeout(timeout);
        connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");
        connection.setRequestProperty("Accept","application/json");
        body=codec.encode(request).getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);
            cancel.check();try(OutputStream out=connection.getOutputStream()){out.write(body);}
            cancel.check();int status=connection.getResponseCode();if(status<200||status>=300)throw new IOException("HTTP_FAILURE");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()) {
                byte[] chunk=new byte[4096];int n;
                while((n=in.read(chunk))!=-1){cancel.check();if(buffer.size()>maxResponseBytes-n)throw new IOException("RESPONSE_LIMIT");buffer.write(chunk,0,n);}
            }
            cancel.check();try{return codec.decode(new String(buffer.toByteArray(),StandardCharsets.UTF_8));}
            catch(Exception malformed){throw new FaceClient.Failure(FaceClient.Error.PROTOCOL);}
        }finally{abort.remove();if(body!=null)java.util.Arrays.fill(body,(byte)0);connection.disconnect();}
    }
}
