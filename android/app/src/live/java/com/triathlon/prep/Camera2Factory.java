package com.triathlon.prep;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** No constructor opens a camera. Caller owns preview Surface and its configured buffer dimensions. */
public final class Camera2Factory {
    interface Opening { void open() throws Exception; }
    static final CameraReleaseGate RELEASE_GATE=new CameraReleaseGate();
    private Camera2Factory() { }
    public static CameraModule create(Context context, Surface previewSurface, long timeoutMillis) {
        return create(context,previewSurface,timeoutMillis,()->{});
    }
    static CameraModule create(Context context,Surface previewSurface,long timeoutMillis,Runnable released) {
        if (context == null || previewSurface == null) throw new IllegalArgumentException("Context and preview Surface required");
        Context app = context.getApplicationContext();
        AtomicBoolean returned=new AtomicBoolean();Runnable ack=()->{if(returned.compareAndSet(false,true))released.run();};
        return new CameraModule(config -> new Backend(app, previewSurface, config,ack), timeoutMillis,ack);
    }
    /** JPEG-only standard session. No camera/permission action is performed by construction. */
    public static CameraModule create(Context context,long timeoutMillis) {
        if(context==null)throw new IllegalArgumentException("Context required");
        Context app=context.getApplicationContext();
        return new CameraModule(config->new Backend(app,null,config,()->{}),timeoutMillis);
    }
    /** API26 field references are isolated and reached only after the SDK/advertised-key guard. */
    static final class Api26 {
        static boolean hasZslControl(CameraCharacteristics characteristics) {
            return Boolean.TRUE.equals(zslControl(characteristics));
        }
        static Boolean zslControl(CameraCharacteristics characteristics) {
            List<CaptureRequest.Key<?>> keys = characteristics.getAvailableCaptureRequestKeys();
            return keys==null?null:keys.contains(CaptureRequest.CONTROL_ENABLE_ZSL);
        }
        static void disableZsl(CaptureRequest.Builder request, boolean available) throws CameraModule.Failure {
            FreshCapturePolicy.disableZsl(Build.VERSION.SDK_INT, available, new FreshCapturePolicy.Control() {
                public void set(boolean enabled) { request.set(CaptureRequest.CONTROL_ENABLE_ZSL, enabled); }
                public Boolean get() { return request.get(CaptureRequest.CONTROL_ENABLE_ZSL); }
            });
        }
    }
    private static final class Backend implements CameraModule.Backend {
        private final Context app;
        private final Surface preview;
        private final CameraModule.Config config;
        private final Runnable released;
        private boolean releaseNotified;
        private final AtomicBoolean closed = new AtomicBoolean();
        // Platform owns main looper. Late device/session callbacks still close their received resources
        // after our worker quits, rather than disappearing on an already-stopped callback looper.
        private final Handler callbacks = new Handler(Looper.getMainLooper());
        private HandlerThread thread;
        private Handler work;
        private volatile CameraDevice device;
        private volatile CameraCaptureSession session;
        private ImageReader images;
        private CameraModule.Events events;
        private CameraModule.PreviewInfo orientation;
        private boolean zslControlAvailable;
        private boolean openRequested,deviceClosed,resourcesReleased;
        private long ticket, pendingTimestamp,lastSensorFrame=-1,lastSensorTimestamp;
        private final CaptureFreshnessProof freshness=new CaptureFreshnessProof();
        private CaptureFreshnessProof.Lease activeProof;
        private byte[] pendingJpeg;
        Backend(Context app, Surface preview, CameraModule.Config config,Runnable released) {
            this.app = app; this.preview = preview; this.config = config;this.released=released;
        }
        public void open(CameraModule.Events events) throws Exception {
            if (closed.get()) return;
            this.events = events;
            if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new CameraModule.Failure(CameraModule.Error.PERMISSION_DENIED);
            if (preview!=null&&!preview.isValid()) throw new CameraModule.Failure(CameraModule.Error.INVALID_CONFIG);
            CameraManager manager = (CameraManager)app.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null || !Arrays.asList(manager.getCameraIdList()).contains(config.cameraId))
                throw new CameraModule.Failure(CameraModule.Error.NO_CAMERA);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(config.cameraId);
            zslControlAvailable=FreshCapturePolicy.controlled(Build.VERSION.SDK_INT,Build.VERSION.SDK_INT>=26?Api26.zslControl(characteristics):null);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (map == null || sensor == null || facing == null
                    || (preview!=null&&(!map.isOutputSupportedFor(preview)||!supports(map.getOutputSizes(ImageFormat.PRIVATE), config.previewWidth, config.previewHeight)))
                    || !supports(map.getOutputSizes(ImageFormat.JPEG), config.jpegWidth, config.jpegHeight))
                throw new CameraModule.Failure(CameraModule.Error.INVALID_CONFIG);
            orientation = CameraModule.orientation(sensor, facing, config);
            synchronized (this) {
                if (closed.get()) return;
                thread = new HandlerThread("camera2-prep"); thread.start(); work = new Handler(thread.getLooper());
                images = ImageReader.newInstance(config.jpegWidth, config.jpegHeight, ImageFormat.JPEG, 2);
                images.setOnImageAvailableListener(this::onImage, work);
            }
            requestOpen(()->manager.openCamera(config.cameraId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice opened) {
                    synchronized (Backend.this) {
                        if (closed.get()) { opened.close(); return; }
                        device = opened;
                        if (!work.post(() -> configure(opened))) { opened.close(); fault(CameraModule.Error.DEVICE_ERROR); }
                    }
                }
                public void onDisconnected(CameraDevice camera) { camera.close(); fault(CameraModule.Error.DISCONNECTED); }
                public void onError(CameraDevice camera, int error) { camera.close(); fault(CameraModule.Error.DEVICE_ERROR); }
                public void onClosed(CameraDevice camera){synchronized(Backend.this){deviceClosed=true;releaseLease();}}
            }, callbacks));
        }
        private void requestOpen(Opening opening) throws Exception {
            synchronized(this){
                if(closed.get())return;
                if(!RELEASE_GATE.acquire(this))throw new CameraModule.Failure(CameraModule.Error.BUSY);
                openRequested=true;
            }
            try{opening.open();}
            catch(Exception|LinkageError ex){synchronized(this){openRequested=false;RELEASE_GATE.released(this);releaseLease();}throw ex;}
        }
        private static boolean supports(Size[] sizes, int width, int height) {
            if (sizes != null) for (Size size : sizes) if (size.getWidth() == width && size.getHeight() == height) return true;
            return false;
        }
        private void configure(CameraDevice camera) {
            if (closed.get()) return;
            try {
                List<Surface> targets=preview==null?java.util.Collections.singletonList(images.getSurface()):Arrays.asList(preview,images.getSurface());
                camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession configured) {
                        synchronized (Backend.this) {
                            if (closed.get()) { configured.close(); return; }
                            session = configured;
                            if (!work.post(() -> repeat(camera, configured))) { configured.close(); fault(CameraModule.Error.DEVICE_ERROR); }
                        }
                    }
                    public void onConfigureFailed(CameraCaptureSession failed) {
                        failed.close(); fault(CameraModule.Error.CONFIGURATION_FAILED);
                    }
                }, callbacks);
            } catch (CameraAccessException | RuntimeException ex) { fault(CameraModule.Error.CONFIGURATION_FAILED); }
        }
        private void repeat(CameraDevice camera, CameraCaptureSession configured) {
            if (closed.get()) return;
            if(preview==null){events.ready(orientation);return;}
            try {
                CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(preview);
                final CaptureRequest repeating=request.build();
                configured.setRepeatingRequest(repeating, new CameraCaptureSession.CaptureCallback(){
                    public void onCaptureCompleted(CameraCaptureSession source,CaptureRequest request,TotalCaptureResult result){
                        Long timestamp=result.get(TotalCaptureResult.SENSOR_TIMESTAMP);
                        if(!closed.get()&&source==session&&source==configured&&request==repeating&&timestamp!=null&&timestamp>0){observeSensor(result.getFrameNumber(),timestamp);events.frame(timestamp);}
                    }
                    public void onCaptureFailed(CameraCaptureSession source,CaptureRequest request,CaptureFailure failure){if(source==session&&request==repeating)fault(CameraModule.Error.DEVICE_ERROR);}
                }, work);
                events.ready(orientation);
            } catch (CameraAccessException | RuntimeException ex) { fault(CameraModule.Error.DEVICE_ERROR); }
        }
        public synchronized void capture(long requestedTicket) throws Exception {
            if (closed.get() || work == null || session == null) throw new CameraModule.Failure(CameraModule.Error.CAPTURE_FAILED);
            if (!work.post(() -> shoot(requestedTicket))) throw new CameraModule.Failure(CameraModule.Error.CAPTURE_FAILED);
        }
        private void observeSensor(long frame,long timestamp){if(frame>lastSensorFrame&&timestamp>lastSensorTimestamp){lastSensorFrame=frame;lastSensorTimestamp=timestamp;}}
        private void shoot(long requestedTicket) {
            if (closed.get()) return;
            try {
                Image old;
                while ((old = images.acquireNextImage()) != null) old.close();
                erasePending(); ticket = requestedTicket;
                CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                if(zslControlAvailable)Api26.disableZsl(request,true);
                request.addTarget(images.getSurface()); request.setTag(requestedTicket);
                request.set(CaptureRequest.JPEG_ORIENTATION, orientation.jpegOrientationDegrees);
                final CaptureRequest submitted=request.build();final CameraCaptureSession submittedSession=session;
                final CaptureFreshnessProof.Lease proof=freshness.begin(submittedSession,submitted,requestedTicket,zslControlAvailable,lastSensorFrame,lastSensorTimestamp);
                activeProof=proof;final int[] sequence={-1};
                sequence[0]=submittedSession.capture(submitted, new CameraCaptureSession.CaptureCallback() {
                    private boolean matches(CameraCaptureSession source,CaptureRequest request) { return !closed.get()&&source==session&&freshness.matches(proof,source,request)&&request.getTag() instanceof Long&&(Long)request.getTag()==proof.ticket; }
                    public void onCaptureStarted(CameraCaptureSession source, CaptureRequest request, long timestamp, long frameNumber) {
                        if (!matches(source,request)) return;
                        freshness.started(proof,source,request,frameNumber,timestamp);flushPending();
                    }
                    public void onCaptureCompleted(CameraCaptureSession source, CaptureRequest request, TotalCaptureResult result) {
                        if (!matches(source,request)) return;
                        Long timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP);
                        if(timestamp!=null){freshness.completed(proof,source,request,result.getFrameNumber(),timestamp);observeSensor(result.getFrameNumber(),timestamp);flushPending();}
                    }
                    public void onCaptureFailed(CameraCaptureSession source, CaptureRequest request, CaptureFailure failure) {
                        if (matches(source,request)) fault(CameraModule.Error.CAPTURE_FAILED);
                    }
                    public void onCaptureSequenceAborted(CameraCaptureSession source, int aborted) { if(!closed.get()&&source==session&&freshness.current(proof)&&sequence[0]>=0&&aborted==sequence[0])fault(CameraModule.Error.CAPTURE_FAILED); }
                }, work);
            } catch (CameraModule.Failure ex) { fault(ex.error); }
            catch (SecurityException ex) { fault(CameraModule.Error.PERMISSION_DENIED); }
            catch (CameraAccessException | RuntimeException ex) { fault(CameraModule.Error.CAPTURE_FAILED); }
        }
        private void onImage(ImageReader source) {
            Image image = null;
            try {
                if(source!=images||closed.get())return;
                image = source.acquireLatestImage();
                if (image == null || closed.get() || ticket == 0||!freshness.current(activeProof)) return;
                if (image.getFormat() != ImageFormat.JPEG || image.getPlanes().length == 0) { fault(CameraModule.Error.INVALID_IMAGE); return; }
                long timestamp = image.getTimestamp();
                if(!freshness.canStage(activeProof,timestamp))return;
                long expected=freshness.expected(activeProof);if(expected!=0&&!CameraModule.sameExposure(expected,timestamp))return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                if (buffer.remaining() < 4 || buffer.remaining() > 8 * 1024 * 1024) { fault(CameraModule.Error.INVALID_IMAGE); return; }
                erasePending(); pendingTimestamp = timestamp; pendingJpeg = new byte[buffer.remaining()]; buffer.get(pendingJpeg);
            } catch (RuntimeException ex) { if (!closed.get()) fault(CameraModule.Error.INVALID_IMAGE); }
            finally {
                if (image != null) {
                    try { image.close(); }
                    catch (RuntimeException ex) { if (!closed.get()) fault(CameraModule.Error.INVALID_IMAGE); }
                }
            }
            flushPending();
        }
        private void flushPending() {
            if (pendingJpeg == null || freshness.expected(activeProof)==0) return;
            byte[] jpeg = pendingJpeg; long timestamp = pendingTimestamp;
            pendingJpeg = null; pendingTimestamp = 0;
            try {
                if (!closed.get() && freshness.acceptImage(activeProof,timestamp)) events.image(ticket, timestamp, jpeg);
            } finally { Arrays.fill(jpeg, (byte)0); }
        }
        private void erasePending() { if (pendingJpeg != null) Arrays.fill(pendingJpeg, (byte)0); pendingJpeg = null; pendingTimestamp = 0; }
        private void fault(CameraModule.Error error) { freshness.cancel();if (!closed.get() && events != null) events.fail(error); }
        public synchronized void close() {
            if (closed.getAndSet(true)) return;
            freshness.cancel();
            if (work != null && !work.post(this::release)) release();
            else if (work == null) release();
        }
        private synchronized void releaseLease(){if(resourcesReleased&&(!openRequested||deviceClosed)){
            RELEASE_GATE.released(this);if(!releaseNotified){releaseNotified=true;released.run();}
        }}
        private void release() {
            freshness.cancel();activeProof=null;
            erasePending();
            try { if (session != null) session.close(); }
            catch (RuntimeException ignored) { }
            finally {
                session = null;
                try { if (device != null) device.close(); }
                catch (RuntimeException ignored) { }
                finally {
                    device = null;
                    try { if (images != null) images.close(); }
                    catch (RuntimeException ignored) { }
                    finally { images = null; if (thread != null) thread.quitSafely(); synchronized(this){resourcesReleased=true;releaseLease();} }
                }
            }
            // preview Surface is owned by the UI caller and is deliberately not released here.
        }
    }
}
