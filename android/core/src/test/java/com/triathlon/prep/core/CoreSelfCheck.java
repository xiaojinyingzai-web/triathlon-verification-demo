package com.triathlon.prep.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.triathlon.prep.core.Verification.Error;
import com.triathlon.prep.core.Verification.Record;
import static com.triathlon.prep.core.Verification.*;

/** Run with java -ea. Explicit checks also fail when assertions are disabled. No test dependency. */
public final class CoreSelfCheck {
    private static final String NUMBER = "000000000000000001";
    private static int passed;
    private interface Check { void run() throws Exception; }
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    private static void test(String name, Check check) throws Exception {
        check.run(); passed++; System.out.println("PASS " + name);
    }
    private static Card card() { return new Card("虚构测试甲", NUMBER, new byte[]{1}); }
    private static Capture capture() { return new Capture(new byte[]{2}, 1, true); }
    private static Comparison comparison(Liveness live, double score) {
        return new Comparison(live, score, 80.0, ScoreRule.AT_LEAST, "TEST-1");
    }

    private static final class TestClock implements Clock {
        long elapsed, wall = 1_700_000_000_000L;
        public long elapsedMillis() { return elapsed; }
        public long wallMillis() { return wall; }
    }
    private static final class Store implements RecordStore {
        final List<Record> records = new ArrayList<>();
        boolean fail;
        public void save(Record record) throws IOException {
            if (fail) throw new IOException("Injected disk failure");
            records.removeIf(old -> old.sessionId.equals(record.sessionId)); records.add(record);
        }
        public List<Record> list() { return new ArrayList<>(records); }
        public void clear() { records.clear(); }
    }
    private static final class Device implements DeviceAdapter {
        Mode mode = Mode.TEST;
        Reply<Card> read;
        Reply<Capture> capture;
        int captures;
        boolean throwRead, throwCancel;
        public Mode mode() { return mode; }
        public void readCard(String id, Reply<Card> reply) {
            if (throwRead) throw new IllegalStateException("Injected SDK throw"); read = reply;
        }
        public void capture(String id, Reply<Capture> reply) { capture = reply; captures++; }
        public void cancel(String id) { if (throwCancel) throw new IllegalStateException("Cancel throw"); }
    }
    private static final class Algorithm implements AlgorithmAdapter {
        Mode mode = Mode.TEST;
        Reply<Comparison> reply;
        byte[] chip, live;
        int calls;
        public Mode mode() { return mode; }
        public void compare(String id, byte[] chipPhoto, byte[] livePhoto, Reply<Comparison> reply) {
            chip = chipPhoto; live = livePhoto; this.reply = reply; calls++;
        }
        public void cancel(String id) { }
    }
    private static final class Fixture {
        final TestClock clock = new TestClock();
        final Device device = new Device();
        final Algorithm algorithm = new Algorithm();
        final Store store = new Store();
        final Verification flow = new Verification(Mode.TEST, device, algorithm, store, clock, 100);
        void ready() { flow.begin(); device.read.ok(card()); device.capture.ok(capture()); }
        void done() { ready(); algorithm.reply.ok(comparison(Liveness.PASSED, 90)); }
        void expect(Verdict verdict, Error error) {
            Snapshot state = flow.snapshot();
            check(state.stage == Stage.FINISHED && state.result.verdict == verdict && state.result.error == error,
                    "Unexpected terminal result");
        }
    }

    public static void main(String[] args) throws Exception {
        File root = args.length == 0 ? new File("build/self-check-data") : new File(args[0]);
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create test directory");
        test("live defaults never pass", () -> {
            Verification flow = new Verification(Mode.LIVE, Unconnected.device(), Unconnected.algorithm(),
                    new Store(), new TestClock(), 100);
            flow.begin();
            check(flow.snapshot().result.verdict == Verdict.UNAVAILABLE
                    && flow.snapshot().result.error == Error.NOT_CONNECTED, "Live default must be unavailable");
        });
        test("simulation adapter rejected by live controller", () -> {
            Fixture f = new Fixture();
            Verification live = new Verification(Mode.LIVE, f.device, f.algorithm, f.store, f.clock, 100);
            live.begin(); check(live.snapshot().result.error == Error.WRONG_MODE, "Wrong-mode adapter accepted");
        });
        test("missing chip photo prevents capture and comparison", () -> {
            Fixture f = new Fixture(); f.flow.begin(); f.device.read.ok(new Card("虚构", NUMBER, null));
            f.expect(Verdict.UNAVAILABLE, Error.MISSING_CHIP_PHOTO);
            check(f.device.captures == 0 && f.algorithm.calls == 0, "Missing chip continued");
        });
        test("invalid card cannot fall back to face-only", () -> {
            Fixture f = new Fixture(); f.flow.begin(); f.device.read.ok(new Card("虚构", "", new byte[]{1}));
            f.expect(Verdict.UNAVAILABLE, Error.INVALID_CARD); check(f.algorithm.calls == 0, "Face-only fallback");
        });
        test("zero and multiple faces never compare", () -> {
            for (int count : new int[]{0, 2, -1}) {
                Fixture f = new Fixture(); f.flow.begin(); f.device.read.ok(card());
                f.device.capture.ok(new Capture(new byte[]{2}, count, true));
                check(f.flow.snapshot().result.verdict == Verdict.UNAVAILABLE && f.algorithm.calls == 0,
                        "Invalid face count compared");
            }
        });
        test("bad capture quality cannot pass", () -> {
            Fixture f = new Fixture(); f.flow.begin(); f.device.read.ok(card());
            f.device.capture.ok(new Capture(new byte[]{2}, 1, false)); f.expect(Verdict.UNAVAILABLE, Error.BAD_CAPTURE);
        });
        test("liveness fail overrides high score", () -> {
            Fixture f = new Fixture(); f.ready(); f.algorithm.reply.ok(comparison(Liveness.FAILED, 999));
            f.expect(Verdict.REJECTED, Error.LIVENESS_FAILED);
        });
        test("unavailable liveness cannot pass", () -> {
            Fixture f = new Fixture(); f.ready(); f.algorithm.reply.ok(comparison(Liveness.UNAVAILABLE, 999));
            f.expect(Verdict.UNAVAILABLE, Error.INVALID_COMPARISON);
        });
        test("SDK score mismatch is rejected", () -> {
            Fixture f = new Fixture(); f.ready(); f.algorithm.reply.ok(comparison(Liveness.PASSED, 79));
            f.expect(Verdict.REJECTED, Error.NO_MATCH);
        });
        test("threshold equality and SDK distance rule", () -> {
            Fixture f = new Fixture(); f.ready(); f.algorithm.reply.ok(comparison(Liveness.PASSED, 80));
            f.expect(Verdict.PASSED, Error.NONE);
            Fixture distance = new Fixture(); distance.ready();
            distance.algorithm.reply.ok(new Comparison(Liveness.PASSED, 3.0, 5.0, ScoreRule.AT_MOST, "TEST-DISTANCE"));
            distance.expect(Verdict.PASSED, Error.NONE);
        });
        test("absent NaN and infinite scores never pass", () -> {
            for (Double score : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY}) {
                Fixture f = new Fixture(); f.ready();
                f.algorithm.reply.ok(new Comparison(Liveness.PASSED, score, 80.0, ScoreRule.AT_LEAST, "TEST"));
                f.expect(Verdict.UNAVAILABLE, Error.INVALID_COMPARISON);
                check(f.flow.snapshot().result.score == null, "Invalid score displayed");
            }
        });
        test("missing SDK threshold or version cannot pass", () -> {
            Fixture f = new Fixture(); f.ready();
            f.algorithm.reply.ok(new Comparison(Liveness.PASSED, 90.0, null, ScoreRule.AT_LEAST, "TEST"));
            f.expect(Verdict.UNAVAILABLE, Error.INVALID_COMPARISON);
            f.flow.begin(); f.device.read.ok(card()); f.device.capture.ok(capture());
            f.algorithm.reply.ok(new Comparison(Liveness.PASSED, 90.0, 80.0, ScoreRule.AT_LEAST, ""));
            f.expect(Verdict.UNAVAILABLE, Error.INVALID_COMPARISON);
        });
        test("only current chip and capture reach algorithm", () -> {
            Fixture f = new Fixture(); f.ready();
            check(f.algorithm.chip[0] == 1 && f.algorithm.live[0] == 2, "Wrong comparison sources");
            f.algorithm.reply.ok(comparison(Liveness.PASSED, 90));
            check(f.flow.snapshot().result.comparisonSource.equals("TEST_FIXTURE_PAIR"), "Wrong source marker");
            check(f.algorithm.chip[0] == 0 && f.algorithm.live[0] == 0, "Controller-owned comparison buffers retained");
        });
        test("changed document drops old read callback", () -> {
            Fixture f = new Fixture(); String first = f.flow.begin(); Reply<Card> old = f.device.read;
            String second = f.flow.begin(); old.ok(card());
            check(!first.equals(second) && f.flow.snapshot().stage == Stage.READING && f.device.captures == 0,
                    "Old card entered new session");
        });
        test("retry drops previous capture callback", () -> {
            Fixture f = new Fixture(); f.flow.begin(); f.device.read.ok(card()); Reply<Capture> old = f.device.capture;
            f.flow.begin(); f.device.read.ok(card()); old.ok(capture());
            check(f.flow.snapshot().stage == Stage.CAPTURING && f.algorithm.calls == 0, "Old capture reused");
        });
        test("next person clears result and drops old comparison", () -> {
            Fixture f = new Fixture(); f.ready(); Reply<Comparison> old = f.algorithm.reply;
            f.flow.next(); f.flow.begin(); old.ok(comparison(Liveness.PASSED, 99));
            check(f.flow.snapshot().stage == Stage.READING && f.flow.snapshot().result == null, "Old comparison reused");
        });
        test("duplicate callbacks advance and save once", () -> {
            Fixture f = new Fixture(); f.flow.begin(); Reply<Card> read = f.device.read;
            read.ok(card()); read.ok(card());
            check(f.device.captures == 1, "Duplicate read advanced twice");
            Reply<Capture> captured = f.device.capture; captured.ok(capture()); captured.ok(capture());
            check(f.algorithm.calls == 1, "Duplicate capture compared twice");
            Reply<Comparison> analyzed = f.algorithm.reply;
            analyzed.ok(comparison(Liveness.PASSED, 90)); analyzed.fail(Error.LICENSE_ERROR);
            check(f.store.records.size() == 1, "Duplicate result saved"); f.expect(Verdict.PASSED, Error.NONE);
        });
        test("cancellation blocks late completion at every stage", () -> {
            for (Stage stage : new Stage[]{Stage.READING, Stage.CAPTURING, Stage.ANALYZING}) {
                Fixture f = new Fixture(); f.flow.begin();
                if (stage != Stage.READING) f.device.read.ok(card());
                if (stage == Stage.ANALYZING) f.device.capture.ok(capture());
                f.flow.cancel();
                if (stage == Stage.READING) f.device.read.ok(card());
                else if (stage == Stage.CAPTURING) f.device.capture.ok(capture());
                else f.algorithm.reply.ok(comparison(Liveness.PASSED, 90));
                f.expect(Verdict.CANCELLED, Error.CANCELLED); check(f.store.records.size() == 1, "Late callback saved");
            }
        });
        test("each stage times out at deadline", () -> {
            for (Stage stage : new Stage[]{Stage.READING, Stage.CAPTURING, Stage.ANALYZING}) {
                Fixture f = new Fixture(); f.flow.begin();
                if (stage != Stage.READING) f.device.read.ok(card());
                if (stage == Stage.ANALYZING) f.device.capture.ok(capture());
                f.clock.elapsed = 100; f.flow.tick(); f.expect(Verdict.UNAVAILABLE, Error.TIMEOUT);
            }
        });
        test("late callback times out even before host tick", () -> {
            Fixture f = new Fixture(); f.ready(); f.clock.elapsed = 101;
            f.algorithm.reply.ok(comparison(Liveness.PASSED, 99)); f.expect(Verdict.UNAVAILABLE, Error.TIMEOUT);
        });
        test("wall clock shift does not affect deadline", () -> {
            Fixture f = new Fixture(); f.flow.begin(); f.clock.wall += 999_999_999;
            f.clock.elapsed = 99; f.flow.tick(); check(f.flow.snapshot().stage == Stage.READING, "Wall clock timed out");
            f.clock.elapsed = 100; f.flow.tick(); f.expect(Verdict.UNAVAILABLE, Error.TIMEOUT);
        });
        test("SDK thrown error and licence failure are unavailable", () -> {
            Fixture f = new Fixture(); f.device.throwRead = true; f.flow.begin(); f.expect(Verdict.UNAVAILABLE, Error.SDK_EXCEPTION);
            Fixture licence = new Fixture(); licence.ready(); licence.algorithm.reply.fail(Error.LICENSE_ERROR);
            licence.expect(Verdict.UNAVAILABLE, Error.LICENSE_ERROR);
        });
        test("cancel exception cannot revive a finished session", () -> {
            Fixture f = new Fixture(); f.device.throwCancel = true; f.done(); f.expect(Verdict.PASSED, Error.NONE);
        });
        test("saving fails independently and retry is idempotent", () -> {
            Fixture f = new Fixture(); f.store.fail = true; f.done(); f.expect(Verdict.PASSED, Error.NONE);
            check(f.flow.snapshot().saveState == SaveState.FAILED && f.store.records.isEmpty(), "False save claim");
            f.store.fail = false; f.flow.retrySave(); f.flow.retrySave();
            check(f.flow.snapshot().saveState == SaveState.SAVED && f.store.records.size() == 1, "Save retry duplicated");
        });
        test("concurrent duplicate completions save exactly once", () -> {
            Fixture f = new Fixture(); f.ready(); AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable callback = () -> { try { f.algorithm.reply.ok(comparison(Liveness.PASSED, 90)); }
                catch (Throwable ex) { failure.set(ex); } };
            Thread a = new Thread(callback), b = new Thread(callback); a.start(); b.start(); a.join(); b.join();
            check(failure.get() == null && f.store.records.size() == 1, "Concurrent duplicate corrupts result");
        });
        test("reset and new session cannot discard an unsaved result", () -> {
            Fixture f = new Fixture(); f.store.fail = true; f.done();
            String id = f.flow.snapshot().sessionId;
            try { f.flow.next(); throw new AssertionError("Reset discarded an unsaved result"); }
            catch (IllegalStateException expected) { }
            try { f.flow.begin(); throw new AssertionError("Begin discarded an unsaved result"); }
            catch (IllegalStateException expected) { }
            check(f.flow.snapshot().sessionId.equals(id) && f.flow.snapshot().saveState == SaveState.FAILED,
                    "Unsaved result lost");
            f.store.fail = false; f.flow.retrySave(); f.flow.next();
            check(f.flow.snapshot().stage == Stage.IDLE && f.store.records.size() == 1, "Recovery blocked reset");
        });
        test("explicit cleanup invalidates active callbacks and clears history", () -> {
            Fixture f = new Fixture(); f.ready(); Reply<Comparison> old = f.algorithm.reply;
            f.flow.clearRecords(); old.ok(comparison(Liveness.PASSED, 99));
            check(f.store.records.isEmpty() && f.flow.snapshot().stage == Stage.IDLE
                    && f.flow.snapshot().result == null, "Clear revived a stale result");
        });
        test("persist query reopen clear and privacy", () -> {
            File directory = Files.createTempDirectory(root.toPath(), "persist-").toFile();
            FileRecordStore logs = new FileRecordStore(directory); Fixture f = new Fixture(); f.done();
            logs.save(f.flow.snapshot().result); logs.save(f.flow.snapshot().result);
            List<Record> reopened = new FileRecordStore(directory).list();
            check(reopened.size() == 1 && reopened.get(0).score == 90.0 && reopened.get(0).mode == Mode.TEST,
                    "Record did not survive reopen or retry duplicated");
            String raw = new String(Files.readAllBytes(new File(directory, "records.bin").toPath()), StandardCharsets.ISO_8859_1);
            check(!raw.contains(NUMBER) && !raw.contains("虚构测试甲"), "Private data written to logs");
            logs.clear(); check(new FileRecordStore(directory).list().isEmpty(), "Clear did not survive reopen");
            check(!new File(directory, "records.bin.bak").exists() && !new File(directory, "records.bin.new").exists(),
                    "Clear left previous data snapshots");
        });
        test("interrupted replace recovers last committed history", () -> {
            File directory = Files.createTempDirectory(root.toPath(), "recovery-").toFile();
            FileRecordStore logs = new FileRecordStore(directory); Fixture f = new Fixture(); f.done(); logs.save(f.flow.snapshot().result);
            File committed = new File(directory, "records.bin");
            check(committed.renameTo(new File(directory, "records.bin.bak")), "Cannot stage interruption");
            Files.write(committed.toPath(), new byte[]{0});
            Files.write(new File(directory, "records.bin.new").toPath(), new byte[]{0});
            check(new FileRecordStore(directory).list().size() == 1, "Interrupted snapshot lost history");
        });
        test("corrupt history reports error instead of empty", () -> {
            File directory = Files.createTempDirectory(root.toPath(), "corrupt-").toFile();
            Files.write(new File(directory, "records.bin").toPath(), new byte[]{0});
            try { new FileRecordStore(directory).list(); throw new AssertionError("Corruption hidden"); }
            catch (IOException expected) { }
        });
        test("storage failure preserves previous committed history", () -> {
            File directory = Files.createTempDirectory(root.toPath(), "write-fail-").toFile();
            FileRecordStore logs = new FileRecordStore(directory); Fixture f = new Fixture(); f.done(); logs.save(f.flow.snapshot().result);
            // A non-empty directory occupies the temporary file: no OS-specific chmod assumption.
            File blocked = new File(directory, "records.bin.new"); check(blocked.mkdir(), "Cannot stage failure");
            Files.write(new File(blocked, "block").toPath(), new byte[]{0});
            try { logs.save(f.flow.snapshot().result); throw new AssertionError("Storage failure hidden"); }
            catch (IOException expected) { }
            Files.delete(new File(blocked, "block").toPath()); Files.delete(blocked.toPath());
            check(new FileRecordStore(directory).list().size() == 1, "Write failure erased history");
        });
        test("bit-flipped valid score is detected by log checksum", () -> {
            File directory = Files.createTempDirectory(root.toPath(), "checksum-").toFile();
            FileRecordStore logs = new FileRecordStore(directory); Fixture f = new Fixture(); f.done(); logs.save(f.flow.snapshot().result);
            File snapshot = new File(directory, "records.bin"); byte[] data = Files.readAllBytes(snapshot.toPath());
            byte[] scoreBytes = java.nio.ByteBuffer.allocate(8).putDouble(90).array();
            boolean flipped = false;
            for (int i = 0; i <= data.length - scoreBytes.length; i++) {
                if (java.util.Arrays.equals(java.util.Arrays.copyOfRange(data, i, i + 8), scoreBytes)) {
                    data[i + 7] ^= 1; flipped = true; break;
                }
            }
            check(flipped, "Could not stage score corruption"); Files.write(snapshot.toPath(), data);
            try { new FileRecordStore(directory).list(); throw new AssertionError("Valid-value corruption accepted"); }
            catch (IOException expected) { }
        });
        test("two rounds of ten mixed sessions stay independent", () -> {
            Fixture f = new Fixture(); List<String> ids = new ArrayList<>();
            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < 10; i++) {
                    String id = f.flow.begin(); check(!ids.contains(id), "Repeated session ID"); ids.add(id);
                    Reply<Card> old = f.device.read;
                    if (i % 3 == 0) f.flow.cancel();
                    else if (i % 3 == 1) { f.clock.elapsed += 100; f.flow.tick(); }
                    else {
                        f.device.read.ok(card()); f.device.capture.ok(capture());
                        f.algorithm.reply.ok(comparison(Liveness.PASSED, 90));
                    }
                    old.ok(card()); check(f.flow.snapshot().stage == Stage.FINISHED, "Late read restarted session");
                    f.flow.next(); check(f.flow.snapshot().result == null && f.flow.snapshot().stage == Stage.IDLE, "Reset leaked result");
                }
            }
            check(f.store.records.size() == 20, "Mixed sessions missing or duplicated logs");
        });
        System.out.println("SUMMARY " + passed + " checks passed; JVM fixture evidence only, no Android/device/algorithm validation.");
    }
}
