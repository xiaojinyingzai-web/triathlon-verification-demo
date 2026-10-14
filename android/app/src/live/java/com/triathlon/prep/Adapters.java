package com.triathlon.prep;
import android.content.Context;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.core.Unconnected;
final class Adapters {
    static Verification.Mode mode() { return Verification.Mode.LIVE; }
    static Verification.DeviceAdapter device() { return Unconnected.device(); }
    static Verification.AlgorithmAdapter algorithm() { return Unconnected.algorithm(); }
    // Legacy no-context query remains unavailable; public drafts can never authorize execution.
    static LiveRuntime.Config configuration(){return null;}
    static LiveRuntime.Config configuration(Context app){return LiveProductionConfigLoader.load(app).config;}
    static VerificationRuntime runtime(Context app,Verification.RecordStore store,Verification.Clock clock) {
        return runtime(app,store,clock,null);
    }
    static VerificationRuntime runtime(Context app,Verification.RecordStore store,Verification.Clock clock,Verification.RosterProvider roster) {
        LiveProductionConfigLoader.Result prepared=LiveProductionConfigLoader.load(app);
        return prepared.attach(LiveRuntime.create(app,prepared.config,store,clock,roster));
    }
}
