package com.triathlon.prep.cefk.hardware;

import android.content.Context;
import android.graphics.Bitmap;
import com.csht.bean.IdCardData;
import com.csht.enums.DecodeImageType;
import com.csht.enums.ReadCardType;
import com.csht.enums.StatusCode;
import com.csht.listener.ReadCardListener;
import com.csht.readcard.ReadCardManager;
import com.csht.readcard.ReadCardOutMethod;
import com.csht.readcard.ReadSettingBuilder;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Calls only declarations verified in CshtReadCard 4.2.9. No network or application init here. */
public final class CshtReaderFactory implements CefkCardReader.Factory {
    public static final class Configuration {
        public final ReadCardType type;
        private final String serialPath, appId, appKey, appSecret;
        private final int baud;
        private final byte[] samCode;
        private final String serviceHost;private final int servicePort;
        /** Caller must explicitly supply manufacturer-confirmed offline mode and credentials policy.
         * Empty credential strings are permitted only when the manufacturer confirms they are unused.
         * ReadCardManager.init(Application) must already have been separately authorized and performed.
         */
        public Configuration(ReadCardType type, String serialPath, int baud,
                String appId, String appKey, String appSecret, byte[] samCode) {
            this(type,serialPath,baud,appId,appKey,appSecret,samCode,null,0);
        }
        public Configuration(ReadCardType type,String serialPath,int baud,String appId,String appKey,String appSecret,byte[] samCode,String serviceHost,int servicePort){
            if (type != ReadCardType.USB && type != ReadCardType.SAM_USB && type != ReadCardType.SAM_SERIALPORT)
                throw new IllegalArgumentException("Explicit USB or SAM reader mode required");
            if (type == ReadCardType.SAM_SERIALPORT && (serialPath == null
                    || !serialPath.startsWith("/dev/") || baud <= 0))
                throw new IllegalArgumentException("Serial parameters required");
            if (appId == null || appKey == null || appSecret == null)
                throw new IllegalArgumentException("Explicit credential policy required");
            if (samCode != null && samCode.length != 16) throw new IllegalArgumentException("SAM code must be 16 bytes");
            this.type = type; this.serialPath = serialPath; this.baud = baud;
            this.appId = appId; this.appKey = appKey; this.appSecret = appSecret;
            this.samCode = samCode == null ? null : samCode.clone();
            if(serviceHost!=null&&(!"yfs4.sfzydq.com".equals(serviceHost)||servicePort!=9999||type!=ReadCardType.USB))throw new IllegalArgumentException("Explicit supported reader service required");
            this.serviceHost=serviceHost;this.servicePort=servicePort;
        }
    }
    // The vendor manager and its settings are global. A second factory must not reconfigure it.
    private static final Object SDK_LOCK = new Object();
    private static boolean leased;
    private final Context context;
    private final Configuration config;
    public CshtReaderFactory(Context context, Configuration config) {
        if (context == null || config == null) throw new IllegalArgumentException("Context/configuration required");
        this.context = context.getApplicationContext(); this.config = config;
    }
    @Override public CefkCardReader.Backend create() {
        synchronized (SDK_LOCK) {
            if (leased) throw new IllegalStateException("Reader SDK already in use");
            leased = true;
        }
        return new Backend();
    }
    private final class Backend implements CefkCardReader.Backend {
        private ReadCardOutMethod manager;
        private volatile boolean stopped;
        @Override public void initialize(CefkCardReader.Events events) {
            ReadSettingBuilder builder = ReadCardManager.builder(context)
                    .buildReadCardType(config.type)
                    .buildDecodeImageType(DecodeImageType.LOCAL_DECODE)
                    .buildOpenLog(false, "")
                    .buildOpenLocalCache(false)
                    .buildOpenSound(false)
                    .buildResultRepeatIdCard(false)
                    .buildIsNeedMoveCard(true)
                    .buildAppId(config.appId, config.appKey, config.appSecret)
                    // This setting generates card front/back images, NOT the portrait bitmap.
                    .buildNeedIdCardPhoto(false)
                    .buildNeedPassportPhoto(false)
                    .buildDelIdCardPhotoWhiteBg(false)
                    .buildIsAsynApiResult(false)
                    .buildReBootGatTime(0);
            if (config.type == ReadCardType.SAM_SERIALPORT)
                builder.buildSerialPort(config.serialPath, config.baud);
            if (config.samCode != null) builder.buildSAMCode(config.samCode.clone());
            if(config.type==ReadCardType.USB)builder.buildPingNetHeartbeat(false,30000).buildQyWxGroupUrl("","");
            if(config.serviceHost!=null)builder.buildIpAndPort(config.serviceHost,config.servicePort);
            manager = builder.buildCreate();
            manager.readCardInit((code, ignoredMessage) -> {
                if (stopped || code == StatusCode.ININ_ING.getCode()) return;
                if (code == StatusCode.ININ_OK.getCode()) events.initialized();
                else events.failed(CefkCardReader.Error.INIT_FAILED);
            });
        }
        @Override public void read(CefkCardReader.Events events) {
            manager.readCardStart(new ReadCardListener() {
                @Override public void onReadCardState(int code, String ignoredMessage) {
                    if (!stopped && code == StatusCode.READCARD_FAILE.getCode())
                        events.failed(CefkCardReader.Error.READ_FAILED);
                }
                @Override public void onReadIDCardSuccess(IdCardData data) {
                    if (stopped) return;
                    // Restrict to the documented mainland identity type. Other card types need mapping.
                    if (data == null || data.getType() != StatusCode.CARD_IDCARD.getCode()
                            || data.getName() == null || data.getName().trim().isEmpty()
                            || data.getName().length() > 128 || data.getId() == null
                            || !data.getId().matches("[0-9]{17}[0-9Xx]")) {
                        events.failed(CefkCardReader.Error.INVALID_CARD); return;
                    }
                    Bitmap bitmap = data.getBitmap();
                    if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() < 1
                            || bitmap.getHeight() < 1 || (long) bitmap.getWidth() * bitmap.getHeight() > 4_000_000) {
                        events.failed(CefkCardReader.Error.MISSING_PHOTO); return;
                    }
                    // Do not recycle a Bitmap owned by the SDK, or use getImage() as if it were PNG.
                    LimitedPng output = new LimitedPng(); byte[] png = null;
                    try {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            events.failed(CefkCardReader.Error.MISSING_PHOTO); return;
                        }
                        png = output.toByteArray();
                        events.card(new CefkCardReader.Card(data.getName(), data.getId(), png));
                    } catch (Exception | LinkageError ex) {
                        events.failed(CefkCardReader.Error.SDK_EXCEPTION);
                    } finally {
                        if (png != null) Arrays.fill(png, (byte) 0);
                        output.erase();
                    }
                }
            });
        }
        @Override public void close() {
            stopped = true;
            boolean released = false;
            try {
                if (manager != null) {
                    try { manager.readCardStop(); }
                    finally { manager.readCardReleaseDestory(); }
                }
                released = true;
            } finally {
                // Failure poisons the singleton lease: don't reuse partially released native state.
                if (released) synchronized (SDK_LOCK) { leased = false; }
                manager = null;
            }
        }
    }
    private static final class LimitedPng extends ByteArrayOutputStream {
        @Override public synchronized void write(byte[] bytes, int off, int len) {
            if (len > 8 * 1024 * 1024 - count) throw new IllegalStateException("Photo too large");
            super.write(bytes, off, len);
        }
        @Override public synchronized void write(int value) {
            if (count >= 8 * 1024 * 1024) throw new IllegalStateException("Photo too large");
            super.write(value);
        }
        synchronized void erase() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
