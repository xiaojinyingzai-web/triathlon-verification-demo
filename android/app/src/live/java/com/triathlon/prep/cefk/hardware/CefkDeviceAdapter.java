package com.triathlon.prep.cefk.hardware;

import com.triathlon.prep.core.Verification;
import java.util.Arrays;

/** Existing core integration seam; a missing confirmed capture provider fails closed. */
public final class CefkDeviceAdapter implements Verification.DeviceAdapter, AutoCloseable {
    public interface CapturePort extends AutoCloseable {
        Verification.Mode mode();
        void capture(String sessionId, Verification.Reply<Verification.Capture> reply);
        void cancel(String sessionId);
        void close() throws Exception;
    }
    private final CefkCardReader reader;
    private final CapturePort capture;
    private volatile boolean closed;
    public CefkDeviceAdapter(CefkCardReader reader, CapturePort capture) {
        if (reader == null) throw new IllegalArgumentException("Reader required");
        if (capture != null && capture.mode() != Verification.Mode.LIVE)
            throw new IllegalArgumentException("LIVE capture required");
        this.reader = reader; this.capture = capture;
    }
    @Override public Verification.Mode mode() { return Verification.Mode.LIVE; }
    @Override public void readCard(String sessionId, Verification.Reply<Verification.Card> reply) {
        if (reply == null) throw new IllegalArgumentException("Reply required");
        reader.read(sessionId, new CefkCardReader.Reply() {
            @Override public void card(String id, CefkCardReader.Card card) {
                byte[] png = null; Verification.Card result = null;
                try {
                    png = card.photoPng();
                    result = new Verification.Card(card.name(), card.number(), png);
                    reply.ok(result);
                } catch (RuntimeException ex) {
                    if (result != null) result.close();
                    throw ex;
                } finally {
                    card.close();
                    if (png != null) Arrays.fill(png, (byte) 0);
                }
            }
            @Override public void failed(String id, CefkCardReader.Error error) { reply.fail(map(error)); }
        });
    }
    @Override public void capture(String sessionId, Verification.Reply<Verification.Capture> reply) {
        if (reply == null) throw new IllegalArgumentException("Reply required");
        if (closed || capture == null) reply.fail(Verification.Error.NOT_CONNECTED);
        else capture.capture(sessionId, reply);
    }
    @Override public void cancel(String sessionId) {
        reader.cancel(sessionId);
        if (capture != null) capture.cancel(sessionId);
    }
    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        reader.close();
        if (capture != null) capture.close();
    }
    private static Verification.Error map(CefkCardReader.Error error) {
        switch (error) {
            case UNCONFIGURED: case CLOSED: return Verification.Error.NOT_CONNECTED;
            case INVALID_CARD: return Verification.Error.INVALID_CARD;
            case MISSING_PHOTO: return Verification.Error.MISSING_CHIP_PHOTO;
            case TIMEOUT: return Verification.Error.TIMEOUT;
            case INIT_FAILED: case READ_FAILED: case BUSY: return Verification.Error.DEVICE_ERROR;
            default: return Verification.Error.SDK_EXCEPTION;
        }
    }
}
