package com.triathlon.prep;
import android.content.Context;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.core.Unconnected;
final class Adapters {
    static Verification.Mode mode() { return Verification.Mode.LIVE; }
    static Verification.DeviceAdapter device() { return Unconnected.device(); }
    static Verification.AlgorithmAdapter algorithm() { return Unconnected.algorithm(); }
    // Sole future configuration selection point. Keep null until a user-authorized device batch.
    static LiveRuntime.Config configuration(){return null;}
    static VerificationRuntime runtime(Context app,Verification.RecordStore store,Verification.Clock clock) {
        return LiveRuntime.create(app,configuration(),store,clock);
    }
    static VerificationRuntime runtime(Context app,Verification.RecordStore store,Verification.Clock clock,Verification.RosterProvider roster) {
        return LiveRuntime.create(app,configuration(),store,clock,roster);
    }
}
