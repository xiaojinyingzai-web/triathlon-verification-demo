package com.triathlon.prep;
import android.content.Context;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.core.ReceiptPrintController;
/** Only compiled into simulationDebug, with a separate app ID; bytes are not face images. */
final class Adapters {
    static Verification.Mode mode() { return Verification.Mode.TEST; }
    static VerificationRuntime runtime(Context app,Verification.RecordStore store,Verification.Clock clock) {
        return runtime(app,store,clock,null);
    }
    static VerificationRuntime runtime(Context app,Verification.RecordStore store,Verification.Clock clock,Verification.RosterProvider roster) {
        com.triathlon.prep.intake.CardInbox inbox=new com.triathlon.prep.intake.CardInbox(mode(),device());
        return new VerificationRuntime(new Verification(mode(),inbox,algorithm(),store,clock,15000,roster),true,()->{},null,printer(),clock,15000,
            new com.triathlon.prep.core.CheckInReceipt.Layout(384,12,24),inbox,new com.triathlon.prep.intake.TestCardSource());
    }
    static ReceiptPrintController.Transport printer(){
        return new ReceiptPrintController.Transport(){
            public Verification.Mode mode(){return Verification.Mode.TEST;}
            public void print(String id,java.util.List<String> lines,ReceiptPrintController.Reply reply){
                // The core enforces TEST watermarks/mode before dispatch. No printer SDK exists here.
                reply.accepted();
            }
            public void cancel(String id){}
        };
    }
    static Verification.DeviceAdapter device() {
        return new Verification.DeviceAdapter() {
            public Verification.Mode mode() { return Verification.Mode.TEST; }
            public void readCard(String id, Verification.Reply<Verification.Card> reply) {
                reply.ok(new Verification.Card("合成证件甲", "990101200001010014", new byte[]{1}));
            }
            public void capture(String id, Verification.Reply<Verification.Capture> reply) {
                reply.ok(new Verification.Capture(new byte[]{2}, 1, true));
            }
            public void cancel(String id) { }
        };
    }
    static Verification.AlgorithmAdapter algorithm() {
        return new Verification.AlgorithmAdapter() {
            public Verification.Mode mode() { return Verification.Mode.TEST; }
            public void compare(String id, byte[] chip, byte[] live, Verification.Reply<Verification.Comparison> reply) {
                reply.ok(new Verification.Comparison(Verification.Liveness.PASSED, 90.0, 80.0,
                        Verification.ScoreRule.AT_LEAST, "FIXTURE_NOT_AN_ALGORITHM"));
            }
            public void cancel(String id) { }
        };
    }
}
