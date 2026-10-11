package com.triathlon.prep.core;

/** Default live path deliberately has no hardware/algorithm implementation or simulated success. */
public final class Unconnected {
    private Unconnected() { }
    public static Verification.DeviceAdapter device() {
        return new Verification.DeviceAdapter() {
            public Verification.Mode mode() { return Verification.Mode.LIVE; }
            public void readCard(String id, Verification.Reply<Verification.Card> reply) { reply.fail(Verification.Error.NOT_CONNECTED); }
            public void capture(String id, Verification.Reply<Verification.Capture> reply) { reply.fail(Verification.Error.NOT_CONNECTED); }
            public void cancel(String id) { }
        };
    }
    public static Verification.AlgorithmAdapter algorithm() {
        return new Verification.AlgorithmAdapter() {
            public Verification.Mode mode() { return Verification.Mode.LIVE; }
            public void compare(String id, byte[] chip, byte[] live, Verification.Reply<Verification.Comparison> reply) { reply.fail(Verification.Error.NOT_CONNECTED); }
            public void cancel(String id) { }
        };
    }
}
