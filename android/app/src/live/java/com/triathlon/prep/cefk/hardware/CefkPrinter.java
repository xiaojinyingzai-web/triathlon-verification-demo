package com.triathlon.prep.cefk.hardware;

import java.nio.charset.Charset;

/** Serialized transport. Eligibility/receipt content remain the calling business module's job. */
public final class CefkPrinter implements AutoCloseable {
    public interface Port {
        void open() throws Exception;
        boolean connected();
        int text(String text, String encoding) throws Exception;
        int image(byte[] png, int dotWidth) throws Exception;
        void close() throws Exception;
    }
    public enum State { UNCONFIGURED, CLOSED, BUSY, STALE_SESSION, NOT_CONNECTED, READY, OPEN_REQUESTED, INVALID_PAYLOAD,
        SDK_EXCEPTION, ALREADY_ATTEMPTED, SUBMITTED_UNCONFIRMED }
    public static final class Result {
        public final State state;
        /** SDK return value only: positive values can include partial USB transfers. */
        public final Integer vendorReturn;
        private Result(State state, Integer vendorReturn) { this.state = state; this.vendorReturn = vendorReturn; }
    }
    private final Port port;
    private final Object lock = new Object();
    private final int dotWidth;
    private final String encoding;
    private String sessionId;
    private boolean closed, attempted, ioBusy;
    public CefkPrinter(Port port, int dotWidth, String encoding) {
        if (dotWidth < 1 || dotWidth > 2048 || encoding == null || !Charset.isSupported(encoding))
            throw new IllegalArgumentException("Explicit dot width/encoding required");
        this.port = port; this.dotWidth = dotWidth; this.encoding = encoding;
    }
    /** Observe current connection before an explicit attempt; a previous open request is not readiness. */
    public State connectionState() {
        synchronized (lock) {
            if (closed) return State.CLOSED;
            if (port == null) return State.UNCONFIGURED;
            if (ioBusy) return State.BUSY;
            try { return port.connected() ? State.READY : State.NOT_CONNECTED; }
            catch (RuntimeException | LinkageError ex) { return State.SDK_EXCEPTION; }
        }
    }
    /** Call on a caller-owned I/O executor. open request is not a readiness or printing guarantee. */
    public State open() {
        synchronized (lock) {
            if (closed) return State.CLOSED;
            if (port == null) return State.UNCONFIGURED;
            if (ioBusy) return State.BUSY;
            ioBusy = true;
        }
        try { port.open(); return port.connected() ? State.READY : State.OPEN_REQUESTED; }
        catch (Exception | LinkageError ex) { return State.SDK_EXCEPTION; }
        finally { endIo(); }
    }
    /** Caller may arm ONLY after current-session roster AND valid live verification have passed.
     * For simulation use a fake Port and visibly marked simulation receipt, never this SDK Port.
     */
    public void arm(String currentSessionId) {
        synchronized (lock) {
        if (closed) throw new IllegalStateException("Printer closed");
        if (currentSessionId == null || currentSessionId.trim().isEmpty()) throw new IllegalArgumentException("Session required");
        if (currentSessionId.equals(sessionId)) return; // re-arming cannot silently authorize duplicate output
        sessionId = currentSessionId; attempted = false;
        }
    }
    public Result printText(String currentSessionId, String text) {
        synchronized (lock) {
        State reject = check(currentSessionId);
        if (reject != null) return new Result(reject, null);
        if (text == null || text.isEmpty() || text.length() > 32_768)
            return new Result(State.INVALID_PAYLOAD, null);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 127)
                return new Result(State.INVALID_PAYLOAD, null);
        }
        attempted = true; ioBusy = true;
        }
        try { return new Result(State.SUBMITTED_UNCONFIRMED, port.text(text, encoding)); }
        catch (Exception | LinkageError ex) { return new Result(State.SDK_EXCEPTION, null); }
        finally { endIo(); }
    }
    public Result printPng(String currentSessionId, byte[] png) {
        byte[] copy;
        synchronized (lock) {
        State reject = check(currentSessionId);
        if (reject != null) return new Result(reject, null);
        if (png == null || png.length == 0 || png.length > 8 * 1024 * 1024)
            return new Result(State.INVALID_PAYLOAD, null);
        copy = png.clone(); attempted = true; ioBusy = true;
        }
        try { return new Result(State.SUBMITTED_UNCONFIRMED, port.image(copy, dotWidth)); }
        catch (Exception | LinkageError ex) { return new Result(State.SDK_EXCEPTION, null); }
        finally { java.util.Arrays.fill(copy, (byte) 0); endIo(); }
    }
    private State check(String id) {
        if (closed) return State.CLOSED;
        if (port == null) return State.UNCONFIGURED;
        if (sessionId == null || !sessionId.equals(id)) return State.STALE_SESSION;
        if (ioBusy) return State.BUSY;
        if (attempted) return State.ALREADY_ATTEMPTED;
        try { if (!port.connected()) return State.NOT_CONNECTED; }
        catch (RuntimeException | LinkageError ex) { return State.SDK_EXCEPTION; }
        return null;
    }
    /** Explicit retry only after operator assesses possible partial/duplicate output. */
    public boolean authorizeRetry(String id) {
        synchronized (lock) {
        if (closed || ioBusy || sessionId == null || !sessionId.equals(id)) return false;
        attempted = false; return true;
        }
    }
    /** Call on cancellation/next participant/background before arming another session. */
    public void invalidate() { synchronized (lock) { sessionId = null; attempted = false; } }
    private void endIo() {
        boolean release;
        synchronized (lock) { release = closed; if (!release) ioBusy = false; }
        // Closing during an in-flight native transfer invalidates immediately, then defers release.
        // Completion still means only SUBMITTED_UNCONFIRMED, even if cancellation occurred meanwhile.
        if (release) {
            try { port.close(); }
            catch (Exception | LinkageError ignored) { /* remains closed; never reuse uncertain state */ }
        }
    }
    @Override public void close() throws Exception {
        synchronized (lock) {
            if (closed) return;
            closed = true; sessionId = null; attempted = false;
            if (ioBusy) return;
        }
        if (port != null) port.close();
    }
}
