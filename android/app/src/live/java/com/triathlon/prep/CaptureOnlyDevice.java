package com.triathlon.prep;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.cefk.hardware.CefkDeviceAdapter;
/** Capture owner for automatic intake. It cannot start an independent card read. */
final class CaptureOnlyDevice implements Verification.DeviceAdapter,AutoCloseable {
    private final CefkDeviceAdapter.CapturePort capture;
    private boolean closed;
    CaptureOnlyDevice(CefkDeviceAdapter.CapturePort capture){if(capture==null||capture.mode()!=Verification.Mode.LIVE)throw new IllegalArgumentException("LIVE capture required");this.capture=capture;}
    public Verification.Mode mode(){return Verification.Mode.LIVE;}
    public void readCard(String id,Verification.Reply<Verification.Card> reply){reply.fail(Verification.Error.NOT_CONNECTED);}
    public void capture(String id,Verification.Reply<Verification.Capture> reply){if(closed)reply.fail(Verification.Error.NOT_CONNECTED);else capture.capture(id,reply);}
    public void cancel(String id){capture.cancel(id);}
    public void close() throws Exception{if(closed)return;closed=true;capture.close();}
}
