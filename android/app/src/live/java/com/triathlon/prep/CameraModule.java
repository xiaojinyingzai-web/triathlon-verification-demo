package com.triathlon.prep;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Explicit foreground owner, one preview and one memory capture per session. Not a face detector. */
public final class CameraModule implements AutoCloseable {
    public enum Error { NOT_CONFIGURED, INVALID_CONFIG, PERMISSION_DENIED, NO_CAMERA, DISCONNECTED,
        DEVICE_ERROR, CONFIGURATION_FAILED, CAPTURE_FAILED, INVALID_IMAGE, TIMEOUT, BUSY, CLOSED }
    public interface PreviewReply { void ready(PreviewInfo info); void fail(Error error); default void frame(long timestamp){} }
    public interface CaptureReply { void ok(Frame frame); void fail(Error error); }
    public static final class Config {
        public final String cameraId;
        public final int previewWidth, previewHeight, jpegWidth, jpegHeight;
        public final int displayRotationDegrees, deviceClockwiseDegrees;
        public final Integer externalRotationDegrees;
        public Config(String cameraId, int previewWidth, int previewHeight, int jpegWidth, int jpegHeight,
                int displayRotationDegrees, int deviceClockwiseDegrees, Integer externalRotationDegrees) {
            if (cameraId == null || cameraId.trim().isEmpty() || !dimension(previewWidth) || !dimension(previewHeight)
                    || !dimension(jpegWidth) || !dimension(jpegHeight) || !angle(displayRotationDegrees)
                    || !angle(deviceClockwiseDegrees) || (externalRotationDegrees != null && !angle(externalRotationDegrees)))
                throw new IllegalArgumentException("Explicit camera ID, sizes and orientation required");
            this.cameraId = cameraId; this.previewWidth = previewWidth; this.previewHeight = previewHeight;
            this.jpegWidth = jpegWidth; this.jpegHeight = jpegHeight;
            this.displayRotationDegrees = displayRotationDegrees; this.deviceClockwiseDegrees = deviceClockwiseDegrees;
            this.externalRotationDegrees = externalRotationDegrees;
        }
        private static boolean dimension(int value) { return value > 0 && value <= 8192; }
    }
    public static final class PreviewInfo {
        public final int previewRotationDegrees, jpegOrientationDegrees;
        public final boolean mirrorPreview;
        PreviewInfo(int previewRotationDegrees, int jpegOrientationDegrees, boolean mirrorPreview) {
            this.previewRotationDegrees = previewRotationDegrees; this.jpegOrientationDegrees = jpegOrientationDegrees;
            this.mirrorPreview = mirrorPreview;
        }
    }
    /** Requested JPEG orientation is metadata, NOT proof that HAL rotated pixels. Honor actual EXIF. */
    public static final class Frame implements AutoCloseable {
        public final String sessionId;
        public final long sensorTimestamp;
        public final int requestedJpegOrientationDegrees;
        public final boolean mirrorRequested = false; // No pixel-mirroring operation; OEM pixels still need device validation.
        private byte[] jpeg;
        Frame(String sessionId, long timestamp, int orientation, byte[] jpeg) {
            this.sessionId = sessionId; this.sensorTimestamp = timestamp;
            this.requestedJpegOrientationDegrees = orientation; this.jpeg = jpeg.clone();
        }
        public synchronized byte[] copyJpeg() {
            if (jpeg == null) throw new IllegalStateException("Frame closed");
            return jpeg.clone();
        }
        @Override public synchronized void close() { if (jpeg != null) { Arrays.fill(jpeg, (byte)0); jpeg = null; } }
    }
    interface Factory { Backend create(Config config) throws Exception; }
    interface Backend { void open(Events events) throws Exception; void capture(long ticket) throws Exception; void close(); }
    interface Events { void ready(PreviewInfo info); void image(long ticket, long timestamp, byte[] jpeg); void fail(Error error); default void frame(long timestamp){} }
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final Error error;
        Failure(Error error) { super(error.name()); this.error = error; }
    }
    private enum Stage { OPENING, PREVIEW, CAPTURING }
    private static final class Session {
        final String id;
        final PreviewReply previewReply;
        Backend backend;
        PreviewInfo info;
        CaptureReply captureReply;
        Stage stage = Stage.OPENING;
        long ticket;
        ScheduledFuture<?> deadline;
        Session(String id, PreviewReply reply) { this.id = id; this.previewReply = reply; }
    }
    private final Object lock = new Object();
    private final Factory factory;
    private final long timeoutMillis;
    private final Runnable abandoned;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "camera-prep-worker"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "camera-prep-timeout"));
    private boolean closed;
    private long nextTicket;
    private Session current;
    private static Thread daemon(Runnable runnable, String name) { Thread t = new Thread(runnable, name); t.setDaemon(true); return t; }
    CameraModule(Factory factory, long timeoutMillis) {
        this(factory,timeoutMillis,()->{});
    }
    CameraModule(Factory factory, long timeoutMillis,Runnable abandoned) {
        if (timeoutMillis < 1 || timeoutMillis > 300_000) throw new IllegalArgumentException("Invalid camera timeout");
        this.factory = factory; this.timeoutMillis = timeoutMillis;this.abandoned=abandoned;
    }
    public static CameraModule unconfigured() { return new CameraModule(null, 15_000); }
    static boolean angle(int degrees) { return degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270; }
    static boolean sameExposure(long expected, long actual) { return expected > 0 && expected == actual; }
    static PreviewInfo orientation(int sensor, int lensFacing, Config config) throws Failure {
        // Android CameraCharacteristics: FRONT=0, BACK=1, EXTERNAL=2. External mounting must be supplied.
        if (lensFacing == 2) {
            if (config.externalRotationDegrees == null) throw new Failure(Error.INVALID_CONFIG);
            return new PreviewInfo(config.externalRotationDegrees, config.externalRotationDegrees, false);
        }
        if (!angle(sensor) || (lensFacing != 0 && lensFacing != 1)) throw new Failure(Error.INVALID_CONFIG);
        boolean front = lensFacing == 0;
        int preview = (sensor - config.displayRotationDegrees * (front ? 1 : -1) + 360) % 360;
        int jpeg = (sensor + config.deviceClockwiseDegrees * (front ? -1 : 1) + 360) % 360;
        return new PreviewInfo(preview, jpeg, front);
    }
    public void start(String id, Config config, PreviewReply reply) {
        if (reply == null) throw new IllegalArgumentException("Preview reply required");
        Error reject = null;
        synchronized (lock) {
            if (closed) reject = Error.CLOSED;
            else if (factory == null || config == null) reject = Error.NOT_CONFIGURED;
            else if (id == null || id.trim().isEmpty()) reject = Error.INVALID_CONFIG;
            else if (current != null) reject = Error.BUSY;
            else {
                Session session = new Session(id, reply); current = session;
                deadline(session);
                worker.execute(() -> open(session, config));
            }
        }
        if (reject != null) reply.fail(reject);
    }
    private void deadline(Session session) {
        session.deadline = timer.schedule(() -> fail(session, Error.TIMEOUT), timeoutMillis, TimeUnit.MILLISECONDS);
    }
    private boolean active(Session session) { synchronized (lock) { return !closed && current == session; } }
    private void open(Session session, Config config) {
        Backend backend = null;
        try {
            if (!active(session)) return;
            backend = factory.create(config);
            if (backend == null) throw new Failure(Error.NO_CAMERA);
            boolean attached;
            synchronized (lock) {
                attached = !closed && current == session;
                if (attached) session.backend = backend;
            }
            if (!attached) { backend.close(); return; }
            backend.open(new Events() {
                public void ready(PreviewInfo info) { previewReady(session, info); }
                public void image(long ticket, long timestamp, byte[] jpeg) { received(session, ticket, timestamp, jpeg); }
                public void fail(Error error) { CameraModule.this.fail(session, error); }
                public void frame(long timestamp) {
                    synchronized(lock){if(closed||current!=session||session.stage!=Stage.PREVIEW||timestamp<=0)return;}
                    session.previewReply.frame(timestamp);
                }
            });
        } catch (Failure ex) { fail(session, ex.error); }
        catch (SecurityException ex) { fail(session, Error.PERMISSION_DENIED); }
        catch (Exception | LinkageError ex) { fail(session, Error.DEVICE_ERROR); }
    }
    private void previewReady(Session session, PreviewInfo info) {
        if (info == null) { fail(session, Error.CONFIGURATION_FAILED); return; }
        synchronized (lock) {
            if (closed || current != session || session.stage != Stage.OPENING) return;
            session.info = info; session.stage = Stage.PREVIEW; session.deadline.cancel(false);
        }
        try { session.previewReply.ready(info); }
        catch (RuntimeException ex) { cancel(session.id); }
    }
    public void capture(String id, CaptureReply reply) {
        if (reply == null) throw new IllegalArgumentException("Capture reply required");
        Error reject = null;
        synchronized (lock) {
            if (closed) reject = Error.CLOSED;
            else if (current == null) reject = Error.NOT_CONFIGURED;
            else if (!current.id.equals(id)) reject = Error.INVALID_CONFIG;
            else if (current.stage != Stage.PREVIEW) reject = Error.BUSY;
            else {
                Session session = current; session.stage = Stage.CAPTURING;
                session.captureReply = reply; session.ticket = ++nextTicket; deadline(session);
                worker.execute(() -> {
                    if (!active(session)) return;
                    try { session.backend.capture(session.ticket); }
                    catch (Failure ex) { fail(session, ex.error); }
                    catch (SecurityException ex) { fail(session, Error.PERMISSION_DENIED); }
                    catch (Exception | LinkageError ex) { fail(session, Error.CAPTURE_FAILED); }
                });
            }
        }
        if (reject != null) reply.fail(reject);
    }
    private void received(Session session, long ticket, long timestamp, byte[] jpeg) {
        try {
            synchronized (lock) {
                if (closed || current != session || session.stage != Stage.CAPTURING || ticket != session.ticket) return;
            }
            if (jpeg == null || jpeg.length < 4 || jpeg.length > 8 * 1024 * 1024 || timestamp <= 0
                    || jpeg[0] != (byte)0xff || jpeg[1] != (byte)0xd8) { fail(session, Error.INVALID_IMAGE); return; }
            Frame frame;
            synchronized (lock) {
                if (closed || current != session) return;
                frame = new Frame(session.id, timestamp, session.info.jpegOrientationDegrees, jpeg);
                detach(session);
            }
            if (!closeBackend(session)) { frame.close(); session.captureReply.fail(Error.DEVICE_ERROR); return; }
            try { session.captureReply.ok(frame); }
            catch (RuntimeException ex) { frame.close(); }
        } finally { if (jpeg != null) Arrays.fill(jpeg, (byte)0); }
    }
    private void detach(Session session) {
        current = null;
        if (session.deadline != null) session.deadline.cancel(false);
    }
    private boolean closeBackend(Session session) {
        if (session.backend != null) {
            try { session.backend.close(); } catch (RuntimeException ex) { return false; }
        } else abandoned.run();
        return true;
    }
    private void fail(Session session, Error error) {
        synchronized (lock) {
            if (closed || current != session) return;
            detach(session);
        }
        closeBackend(session);
        if (session.captureReply != null) session.captureReply.fail(error == null ? Error.DEVICE_ERROR : error);
        else session.previewReply.fail(error == null ? Error.DEVICE_ERROR : error);
    }
    /** Silent cancellation invalidates queued callbacks; caller remains responsible for foreground ownership. */
    public void cancel(String id) {
        Session session;
        synchronized (lock) {
            if (current == null || !current.id.equals(id)) return;
            session = current; detach(session);
        }
        closeBackend(session);
    }
    @Override public void close() {
        Session session;
        synchronized (lock) {
            if (closed) return;
            closed = true; session = current;
            if (session != null) detach(session);
        }
        if (session != null) closeBackend(session);
        worker.shutdown(); timer.shutdownNow();
    }
}
