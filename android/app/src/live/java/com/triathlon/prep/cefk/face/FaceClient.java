package com.triathlon.prep.cefk.face;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Client protocol only. A successful service response is never an identity verdict. */
public final class FaceClient {
    public enum Operation { FaceLive, FaceLiveAnd1V1, FaceLiveQualityCheck }
    public enum ByteFormat { UNCONFIRMED, SIGNED, UNSIGNED }
    public enum SignCase { UNCONFIRMED, LOWER, UPPER }
    public enum Mode { SIMULATION, REAL }
    public enum Error { UNAVAILABLE, INVALID_INPUT, AUTH, SERVICE, PROTOCOL, TIMEOUT, CANCELLED, TRANSPORT }
    public static final class Failure extends Exception {
        public final Error error;
        public Failure(Error error) { super(error.name()); this.error=error; }
    }
    public interface CredentialsProvider { Credentials get() throws Exception; }
    public interface Clock { long millis(); }
    public interface Transport {
        Mode mode();
        Map<String,Object> post(Map<String,Object> request, int timeoutMs, Cancellation cancel) throws Exception;
    }
    public static final class Credentials {
        private final String appId, appKey, appSecret;
        public Credentials(String id,String key,String secret) { appId=id; appKey=key; appSecret=secret; }
        boolean valid() { return present(appId)&&present(appKey)&&present(appSecret); }
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public static final class Config {
        public final Mode mode;
        public final String endpoint, deviceNumber, deviceName;
        public final ByteFormat byteFormat;
        public final SignCase signCase;
        public final boolean imageContractConfirmed, thresholdsConfirmed, realUseAuthorized;
        public final int timeoutMs, maxImageBytes;
        public final long tokenTtlMs;
        public Config(Mode mode,String endpoint,String number,String name,ByteFormat bytes,SignCase signCase,
                      boolean images,boolean thresholds,boolean authorized,int timeout,int maxBytes,long ttl) {
            this.mode=mode;this.endpoint=endpoint;deviceNumber=number;deviceName=name;byteFormat=bytes;
            this.signCase=signCase;imageContractConfirmed=images;thresholdsConfirmed=thresholds;
            realUseAuthorized=authorized;timeoutMs=timeout;maxImageBytes=maxBytes;tokenTtlMs=ttl;
        }
        void check() throws Failure {
            if(mode==null||byteFormat==null||byteFormat==ByteFormat.UNCONFIRMED||signCase==null||signCase==SignCase.UNCONFIRMED
                ||!imageContractConfirmed||!thresholdsConfirmed||!present(deviceNumber)||!present(deviceName)
                ||timeoutMs<=0||maxImageBytes<=0||tokenTtlMs<=0||tokenTtlMs>1800000L) throw new Failure(Error.UNAVAILABLE);
            if(mode==Mode.REAL) {
                if(!realUseAuthorized) throw new Failure(Error.UNAVAILABLE);
                try {
                    java.net.URI uri=new java.net.URI(endpoint);
                    if(!Arrays.asList("http","https").contains(uri.getScheme())||!"yfs4.sfzydq.com".equals(uri.getHost())
                        ||uri.getPort()!=8080||!"/yjm/decode/v3/http".equals(uri.getPath())
                        ||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null) throw new Exception();
                } catch(Exception e) { throw new Failure(Error.UNAVAILABLE); }
            }
        }
    }
    public static final class Cancellation {
        private volatile boolean cancelled;
        private final Set<Runnable> actions=new LinkedHashSet<>();
        interface Registration { void remove(); }
        /** Registration and cancellation share a lock; late registrants are aborted immediately. */
        Registration onCancel(Runnable action) {
            boolean run;
            synchronized(this){run=cancelled;if(!run)actions.add(action);}
            if(run)runAction(action);
            return ()->{synchronized(Cancellation.this){actions.remove(action);}};
        }
        public void cancel() {
            List<Runnable> pending;
            synchronized(this){if(cancelled)return;cancelled=true;pending=new ArrayList<>(actions);actions.clear();}
            for(Runnable action:pending)runAction(action);
        }
        private static void runAction(Runnable action){try{action.run();}catch(RuntimeException ignored){}}
        public boolean isCancelled() { return cancelled; }
        void check() throws Failure { if(cancelled||Thread.currentThread().isInterrupted()) throw new Failure(Error.CANCELLED); }
    }
    public static final class Scores {
        public final Operation operation;
        public final Mode mode;
        public final Double liveGrade, faceGrade;
        private final double[] quality;
        Scores(Operation op,Mode mode,Double live,Double face,double[] quality) {
            operation=op;this.mode=mode;liveGrade=live;faceGrade=face;this.quality=quality;
        }
        public double[] qualityList() { return quality==null?null:quality.clone(); }
    }
    private static final class Token { final String value;final long issued, until; Token(String v,long i,long u){value=v;issued=i;until=u;} }
    private final Config config;
    private final CredentialsProvider credentials;
    private final Transport transport;
    private final Clock clock;
    private final Map<Operation,Token> tokens=new EnumMap<>(Operation.class);
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"cefk-face");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private final Object lifecycle=new Object();
    private Cancellation active;
    public FaceClient(Config config,CredentialsProvider credentials,Transport transport,Clock clock) {
        this.config=config;this.credentials=credentials;this.transport=transport;this.clock=clock;
    }
    /** Run on a background caller. Serialization prevents overlapping requests/token mutation. */
    public synchronized Scores execute(Operation op,byte[][] images,Cancellation cancellation) throws Failure {
        if(cancellation==null)throw new Failure(Error.INVALID_INPUT);
        Cancellation owned=new Cancellation();
        Cancellation.Registration link=cancellation.onCancel(owned::cancel);
        synchronized(lifecycle){if(closed){link.remove();throw new Failure(Error.UNAVAILABLE);}active=owned;}
        try{return executeOwned(op,images,owned);}
        finally{
            link.remove();
            synchronized(lifecycle){if(active==owned)active=null;}
            if(closed)tokens.clear();
        }
    }
    private Scores executeOwned(Operation op,byte[][] images,Cancellation cancellation) throws Failure {
        if(closed||config==null||transport==null||clock==null||credentials==null) throw new Failure(Error.UNAVAILABLE);
        config.check(); if(transport.mode()!=config.mode) throw new Failure(Error.UNAVAILABLE);
        if(cancellation==null||op==null) throw new Failure(Error.INVALID_INPUT);
        cancellation.check();
        int count=op==Operation.FaceLiveAnd1V1?2:1;
        if(images==null||images.length!=count) throw new Failure(Error.INVALID_INPUT);
        List<Object> imageArrays=new ArrayList<>();
        for(byte[] image:images) {
            if(image==null||image.length==0||image.length>config.maxImageBytes) throw new Failure(Error.INVALID_INPUT);
            List<Integer> bytes=new ArrayList<>(image.length);
            for(byte b:image) bytes.add(config.byteFormat==ByteFormat.SIGNED?(int)b:b&255);
            imageArrays.add(bytes);
        }
        Token token=tokens.get(op); long now=clock.millis();
        if(token==null||now<token.issued||now>=token.until) {
            tokens.remove(op);
            Credentials c;
            try { c=credentials.get(); } catch(Exception e) { throw new Failure(Error.UNAVAILABLE); }
            if(c==null||!c.valid()) throw new Failure(Error.UNAVAILABLE);
            Map<String,Object> login=new LinkedHashMap<>();
            login.put("type","login");login.put("appId",c.appId);login.put("appKey",c.appKey);
            login.put("timestamp",now);login.put("sdkCode",op.name());
            login.put("sign",sign(c,now,config.signCase));login.put("deviceNumber",config.deviceNumber);
            login.put("sdkVersion","2.4.0");login.put("deviceName",config.deviceName);
            Map<String,Object> reply=call(login,cancellation);checkReply(reply,"login",true);
            Object value=reply.get("token");if(!(value instanceof String)||!present((String)value)) throw new Failure(Error.PROTOCOL);
            long expires;
            try { expires=Math.addExact(now,config.tokenTtlMs); } catch(ArithmeticException e){throw new Failure(Error.PROTOCOL);}
            token=new Token((String)value,now,expires);tokens.put(op,token);
        }
        Map<String,Object> request=new LinkedHashMap<>();request.put("type",op.name());request.put("token",token.value);
        request.put(count==2?"dataMulti":"data",count==2?imageArrays:imageArrays.get(0));
        Map<String,Object> reply=call(request,cancellation);
        try { checkReply(reply,op.name(),false); } catch(Failure f){if(f.error==Error.SERVICE)tokens.remove(op);throw f;}
        Double live=null,face=null;double[] quality=null;
        if(op==Operation.FaceLiveQualityCheck) {
            Object raw=reply.get("qualityList");if(!(raw instanceof List)||((List<?>)raw).size()!=4) throw new Failure(Error.PROTOCOL);
            quality=new double[4]; for(int i=0;i<4;i++) quality[i]=number(((List<?>)raw).get(i),i==3);
        } else {
            live=number(reply.get("liveGrade"),true);
            if(op==Operation.FaceLiveAnd1V1)face=number(reply.get("faceGrade"),true);
        }
        cancellation.check();return new Scores(op,config.mode,live,face,quality);
    }
    private Map<String,Object> call(Map<String,Object> request,Cancellation cancel) throws Failure {
        cancel.check();Cancellation io=new Cancellation();
        Cancellation.Registration link=cancel.onCancel(io::cancel);
        Future<Map<String,Object>> future=null;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.timeoutMs);
        try {
            io.check();
            try{future=worker.submit(()->transport.post(request,config.timeoutMs,io));}
            catch(RejectedExecutionException closedWorker){throw new Failure(closed?Error.CANCELLED:Error.UNAVAILABLE);}
            for(;;) {
                cancel.check();long remaining=deadline-System.nanoTime();if(remaining<=0)throw new Failure(Error.TIMEOUT);
                try {Map<String,Object> result=future.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(20)),TimeUnit.NANOSECONDS);
                    cancel.check();return result;
                }catch(TimeoutException polling) { }
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(Error.CANCELLED);
        }catch(ExecutionException e){cancel.check();if(e.getCause() instanceof Failure)throw (Failure)e.getCause();
            throw new Failure(e.getCause() instanceof java.net.SocketTimeoutException?Error.TIMEOUT:Error.TRANSPORT);
        }finally{
            link.remove();
            // Future.cancel only interrupts Java code. Cancellation actively aborts the connection.
            if(future!=null&&!future.isDone()){io.cancel();future.cancel(true);}
        }
    }
    private static void checkReply(Map<String,Object> r,String expected,boolean auth) throws Failure {
        if(r==null||!(r.get("code") instanceof String)||(auth&&!expected.equals(r.get("type")))
            ||(r.containsKey("type")&&!expected.equals(r.get("type"))))throw new Failure(Error.PROTOCOL);
        if(!"00".equals(r.get("code")))throw new Failure(auth?Error.AUTH:Error.SERVICE);
    }
    private static double number(Object v,boolean score) throws Failure {
        if(!(v instanceof Number))throw new Failure(Error.PROTOCOL);double n=((Number)v).doubleValue();
        if(Double.isNaN(n)||Double.isInfinite(n)||(score&&(n<0||n>100)))throw new Failure(Error.PROTOCOL);return n;
    }
    static String sign(Credentials c,long timestamp,SignCase casing) throws Failure {
        try {
            byte[] digest=MessageDigest.getInstance("MD5").digest((c.appId+c.appKey+timestamp+c.appSecret).getBytes(StandardCharsets.UTF_8));
            StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));
            return casing==SignCase.UPPER?s.toString().toUpperCase(Locale.ROOT):s.toString();
        }catch(Exception e){throw new Failure(Error.UNAVAILABLE);}
    }
    private static boolean present(String s){return s!=null&&!s.trim().isEmpty();}
    public synchronized void invalidateTokens(){tokens.clear();}
    public void close(){
        Cancellation owned;
        synchronized(lifecycle){closed=true;owned=active;}
        if(owned!=null)owned.cancel();
        worker.shutdownNow();
        // Never wait on execute's monitor while it owns blocked I/O.
        if(owned==null){synchronized(this){tokens.clear();}}
    }
}
