package com.triathlon.prep.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Each adapter callback belongs to one session AND one stage; duplicates cannot advance twice. */
public final class Verification {
    public enum Mode { LIVE, TEST }
    public enum Stage { IDLE, READING, CAPTURING, ANALYZING, FINISHED }
    public enum Verdict { PASSED, REJECTED, UNAVAILABLE, CANCELLED }
    public enum Liveness { PASSED, FAILED, UNAVAILABLE }
    public enum ScoreRule { AT_LEAST, AT_MOST }
    public enum SaveState { NONE, SAVED, FAILED }
    public enum Membership { LEGACY_NOT_EVALUATED, NO_ROSTER, MATCHED, NOT_LISTED, ERROR }
    public interface RosterProvider { RaceRoster current() throws IOException; }
    public enum Error {
        NONE, NOT_CONNECTED, WRONG_MODE, INVALID_CARD, MISSING_CHIP_PHOTO,
        NO_FACE, MULTIPLE_FACES, BAD_CAPTURE, LIVENESS_FAILED, NO_MATCH,
        INVALID_COMPARISON, SDK_EXCEPTION, DEVICE_ERROR, LICENSE_ERROR, TIMEOUT, CANCELLED, PERMISSION_DENIED,
        NO_ROSTER, NOT_LISTED, ROSTER_ERROR
    }

    public interface Clock {
        long elapsedMillis(); // Monotonic; wall clock changes must not extend timeouts.
        long wallMillis();
    }
    public interface Reply<T> { void ok(T value); void fail(Error error); }
    public interface DeviceAdapter {
        Mode mode();
        // Return promptly; use asynchronous callbacks for hardware I/O. After cancel, release SDK buffers.
        void readCard(String sessionId, Reply<Card> reply);
        void capture(String sessionId, Reply<Capture> reply);
        void cancel(String sessionId);
    }
    public interface AlgorithmAdapter {
        Mode mode();
        // Chip and live images MUST come from this invocation. No enrollment/1:N method exists.
        void compare(String sessionId, byte[] chipPhoto, byte[] livePhoto, Reply<Comparison> reply);
        void cancel(String sessionId);
    }
    /** Captures one attributed frame, validates quality/liveness and returns a 1:1 observation.
     * Copies chipPhoto synchronously before returning. onAnalyzing is invoked only after accepting
     * the current frame, before the terminal reply; callbacks must not hold adapter cleanup locks. */
    public interface FrameVerifier {
        Mode mode();
        Error preparationError(); // Read-only; null means configured, never proof of device readiness.
        void captureAndCompare(String sessionId, byte[] chipPhoto, Runnable onAnalyzing, Reply<Comparison> reply);
        void cancel(String sessionId);
    }
    public interface RecordStore {
        void save(Record record) throws IOException;
        List<Record> list() throws IOException;
        void clear() throws IOException;
    }

    public static final class Card implements AutoCloseable {
        public final String name;
        private final String number;
        private final byte[] photo;
        public Card(String name, String number, byte[] chipPhoto) {
            this.name = name;
            this.number = number;
            this.photo = chipPhoto == null ? null : chipPhoto.clone();
        }
        /** Optional presentation receives a copy, never the owned algorithm input. Caller must erase it. */
        public synchronized byte[] photoCopy() { return photo == null ? null : photo.clone(); }
        private synchronized void erase() { if (photo != null) Arrays.fill(photo, (byte) 0); }
        @Override public void close() { erase(); }
    }
    public static final class Capture implements AutoCloseable {
        private final byte[] photo;
        public final int faceCount;
        public final boolean qualityAccepted;
        public Capture(byte[] photo, int faceCount, boolean qualityAccepted) {
            this.photo = photo == null ? null : photo.clone();
            this.faceCount = faceCount;
            this.qualityAccepted = qualityAccepted;
        }
        private void erase() { if (photo != null) Arrays.fill(photo, (byte) 0); }
        @Override public void close() { erase(); }
    }
    public static final class Comparison {
        public final Liveness liveness;
        public final Double score, threshold;
        public final ScoreRule rule;
        public final String algorithmVersion;
        public Comparison(Liveness liveness, Double score, Double threshold,
                          ScoreRule rule, String algorithmVersion) {
            this.liveness = liveness;
            this.score = score;
            this.threshold = threshold;
            this.rule = rule;
            this.algorithmVersion = algorithmVersion;
        }
    }
    public static final class Record {
        public final String sessionId;
        public final long timestamp;
        public final Mode mode;
        public final String maskedNumber;
        public final String comparisonSource;
        public final Verdict verdict;
        public final Error error;
        public final Liveness liveness;
        public final Double score, threshold;
        public final ScoreRule rule;
        public final String algorithmVersion;
        public final String rosterBatchId;
        public final Membership membership;
        /** null means a historical identity-only record, never event acceptance. */
        public final Verdict eventVerdict;
        public Record(String sessionId, long timestamp, Mode mode, String maskedNumber,
                      String comparisonSource, Verdict verdict, Error error, Liveness liveness,
                      Double score, Double threshold, ScoreRule rule, String algorithmVersion) {
            this(sessionId,timestamp,mode,maskedNumber,comparisonSource,verdict,error,liveness,score,threshold,rule,algorithmVersion,"",Membership.LEGACY_NOT_EVALUATED);
        }
        public Record(String sessionId, long timestamp, Mode mode, String maskedNumber,
                      String comparisonSource, Verdict verdict, Error error, Liveness liveness,
                      Double score, Double threshold, ScoreRule rule, String algorithmVersion,
                      String rosterBatchId,Membership membership) {
            this.sessionId = sessionId;
            this.timestamp = timestamp;
            this.mode = mode;
            this.maskedNumber = maskedNumber;
            this.comparisonSource = comparisonSource;
            this.verdict = verdict;
            this.error = error;
            this.liveness = liveness;
            this.score = score;
            this.threshold = threshold;
            this.rule = rule;
            this.algorithmVersion = algorithmVersion;
            this.rosterBatchId=rosterBatchId;this.membership=membership;
            this.eventVerdict=membership==Membership.LEGACY_NOT_EVALUATED?null:
                    verdict==Verdict.CANCELLED?Verdict.CANCELLED:membership==Membership.MATCHED?verdict:
                    membership==Membership.NOT_LISTED?Verdict.REJECTED:Verdict.UNAVAILABLE;
        }
    }
    public static final class Snapshot {
        public final String sessionId;
        public final Stage stage;
        public final Mode mode;
        public final Record result;
        public final SaveState saveState;
        public final String name, maskedNumber;
        public final RaceRoster.Athlete athlete;
        public final Membership membership;
        public final String rosterBatchId;
        public final String eventName;
        private Snapshot(String id, Stage stage, Mode mode, Record result, SaveState saveState,
                String name, String maskedNumber,RaceRoster.Athlete athlete,Membership membership,String rosterBatchId,String eventName) {
            this.sessionId = id; this.stage = stage; this.mode = mode;
            this.result = result; this.saveState = saveState;
            this.name = name; this.maskedNumber = maskedNumber;
            this.athlete=athlete;this.membership=membership;this.rosterBatchId=rosterBatchId;
            this.eventName=eventName;
        }
    }

    private final Mode mode;
    private final DeviceAdapter device;
    private final AlgorithmAdapter algorithm;
    private final FrameVerifier frames;
    private final RecordStore store;
    private final Clock clock;
    private final long stageTimeoutMillis;
    private final RosterProvider rosterProvider;
    private RaceRoster pinnedRoster;
    private RaceRoster.Athlete athlete;
    private Membership membership;
    private String sessionId = "";
    private Stage stage = Stage.IDLE;
    private long deadline;
    private Card card;
    private Capture capture;
    private byte[] comparisonChip, comparisonLive;
    private Record result;
    private SaveState saveState = SaveState.NONE;
    private boolean currentFrameObserved;

    public Verification(Mode mode, DeviceAdapter device, AlgorithmAdapter algorithm,
                        RecordStore store, Clock clock, long stageTimeoutMillis) {
        this(mode,device,algorithm,null,store,clock,stageTimeoutMillis,null);
    }
    public Verification(Mode mode, DeviceAdapter device, AlgorithmAdapter algorithm,
                        RecordStore store, Clock clock, long stageTimeoutMillis,RosterProvider rosterProvider) {
        this(mode,device,algorithm,null,store,clock,stageTimeoutMillis,rosterProvider);
    }
    public static Verification withFrames(Mode mode, DeviceAdapter reader, FrameVerifier frames,
            RecordStore store, Clock clock, long stageTimeoutMillis) {
        return new Verification(mode,reader,null,frames,store,clock,stageTimeoutMillis,null);
    }
    public static Verification withFrames(Mode mode, DeviceAdapter reader, FrameVerifier frames,
            RecordStore store, Clock clock, long stageTimeoutMillis,RosterProvider rosterProvider) {
        return new Verification(mode,reader,null,frames,store,clock,stageTimeoutMillis,rosterProvider);
    }
    private Verification(Mode mode, DeviceAdapter device, AlgorithmAdapter algorithm, FrameVerifier frames,
            RecordStore store, Clock clock, long stageTimeoutMillis,RosterProvider rosterProvider) {
        if (mode == null || device == null || (algorithm == null)==(frames == null) || store == null || clock == null
                || stageTimeoutMillis < 1 || stageTimeoutMillis > 300_000) {
            throw new IllegalArgumentException("Invalid verification configuration");
        }
        this.mode = mode; this.device = device; this.algorithm = algorithm;
        this.frames = frames;
        this.store = store; this.clock = clock; this.stageTimeoutMillis = stageTimeoutMillis;
        this.rosterProvider=rosterProvider;membership=rosterProvider==null?Membership.LEGACY_NOT_EVALUATED:Membership.NO_ROSTER;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(sessionId, stage, mode, result, saveState,
                card == null ? null : card.name,
                card == null ? null : "**************" + card.number.substring(14),athlete,membership,pinnedRoster==null?"":pinnedRoster.batchId,pinnedRoster==null?"":pinnedRoster.eventName);
    }

    /** Begin/retry always makes a new session, even when the same document is used. */
    public synchronized String begin() {
        requireSaved();
        if (active()) finish(Verdict.CANCELLED, Error.CANCELLED, null);
        requireSaved();
        reset();
        sessionId = UUID.randomUUID().toString();
        enter(Stage.READING);
        final String id = sessionId;
        try {
            if(rosterProvider!=null) {
                try{pinnedRoster=rosterProvider.current();}
                catch(IOException | RuntimeException ex){membership=Membership.ERROR;finish(Verdict.UNAVAILABLE,Error.ROSTER_ERROR,null);return id;}
                if(pinnedRoster==null){membership=Membership.NO_ROSTER;finish(Verdict.UNAVAILABLE,Error.NO_ROSTER,null);return id;}
            }
            if (device.mode() != mode || (frames == null ? algorithm.mode() : frames.mode()) != mode) {
                finish(Verdict.UNAVAILABLE, Error.WRONG_MODE, null);
            } else {
                Error preparation=frames==null?null:frames.preparationError();
                if(preparation!=null)finish(Verdict.UNAVAILABLE,preparation,null);
                else device.readCard(id, reply(id, Stage.READING, this::onCard));
            }
        } catch (RuntimeException | LinkageError ex) { failCurrent(id, Stage.READING, Error.SDK_EXCEPTION); }
        return id;
    }

    public synchronized void next() {
        requireSaved();
        if (active()) finish(Verdict.CANCELLED, Error.CANCELLED, null);
        requireSaved();
        reset();
    }

    /** Explicit user-directed test-data deletion also discards any unsaved result. */
    public synchronized void clearRecords() throws IOException {
        if (active()) finish(Verdict.CANCELLED, Error.CANCELLED, null);
        store.clear();
        reset();
    }

    public synchronized void cancel() {
        if (active()) finish(Verdict.CANCELLED, Error.CANCELLED, null);
    }

    /** Called by the host timer. Every callback also checks the deadline. */
    public synchronized void tick() {
        if (active() && clock.elapsedMillis() >= deadline) {
            finish(Verdict.UNAVAILABLE, Error.TIMEOUT, null);
        }
    }

    public synchronized void retrySave() {
        if (result != null && saveState == SaveState.FAILED) persist();
    }

    private interface Accept<T> { void run(T value); }
    private <T> Reply<T> reply(String id, Stage expected, Accept<T> accept) {
        return new Reply<T>() {
            public void ok(T value) {
                synchronized (Verification.this) {
                    try {
                        if (!current(id, expected)) return;
                        try { accept.run(value); }
                        catch (RuntimeException | LinkageError ex) { failCurrent(id, stage, Error.SDK_EXCEPTION); }
                    } finally { discard(value); }
                }
            }
            public void fail(Error error) {
                synchronized (Verification.this) { failCurrent(id, expected, error); }
            }
        };
    }

    private boolean current(String id, Stage expected) {
        if (!sessionId.equals(id) || stage != expected || !active()) return false;
        tick();
        return stage == expected;
    }

    private void failCurrent(String id, Stage expected, Error error) {
        if (current(id, expected)) {
            finish(Verdict.UNAVAILABLE,
                    error == null || error == Error.NONE ? Error.SDK_EXCEPTION : error, null);
        }
    }

    private void onCard(Card value) {
        if (value == null || value.name == null || value.name.trim().isEmpty()
                || value.name.length() > 128 || value.number == null
                || (rosterProvider==null&&!value.number.matches("[0-9]{17}[0-9Xx]"))) {
            finish(Verdict.UNAVAILABLE, Error.INVALID_CARD, null); return;
        }
        if (!validPhoto(value.photo)) {
            finish(Verdict.UNAVAILABLE, Error.MISSING_CHIP_PHOTO, null); return;
        }
        String number=value.number;
        if(rosterProvider!=null) {
            try {number=IdentityNumber.normalize(number);athlete=pinnedRoster.match(number);}
            catch(IllegalArgumentException ex){finish(Verdict.UNAVAILABLE,Error.INVALID_CARD,null);return;}
            if(athlete==null){membership=Membership.NOT_LISTED;finish(Verdict.REJECTED,Error.NOT_LISTED,null);return;}
            membership=Membership.MATCHED;
        }
        card = new Card(value.name, number, value.photo);
        enter(Stage.CAPTURING);
        if (frames == null) device.capture(sessionId, reply(sessionId, Stage.CAPTURING, this::onCapture));
        else {
            final String id=sessionId;
            byte[] chip=card.photo.clone();
            try {
                frames.captureAndCompare(id,chip,() -> {
                    synchronized(Verification.this) {
                        if(current(id,Stage.CAPTURING)){currentFrameObserved=true;enter(Stage.ANALYZING);}
                    }
                },new Reply<Comparison>() {
                    public void ok(Comparison value) {
                        synchronized(Verification.this) {
                            if(!current(id,Stage.ANALYZING))return;
                            try {onComparison(value);}catch(RuntimeException | LinkageError ex){failCurrent(id,Stage.ANALYZING,Error.SDK_EXCEPTION);}
                        }
                    }
                    public void fail(Error error) {
                        synchronized(Verification.this) {
                            if(sessionId.equals(id) && (stage==Stage.CAPTURING || stage==Stage.ANALYZING))failCurrent(id,stage,error);
                        }
                    }
                });
            }finally{Arrays.fill(chip,(byte)0);card.erase();}
        }
    }

    private void onCapture(Capture value) {
        Error invalid = value == null ? Error.BAD_CAPTURE
                : value.faceCount == 0 ? Error.NO_FACE
                : value.faceCount > 1 ? Error.MULTIPLE_FACES
                : value.faceCount != 1 || !value.qualityAccepted || !validPhoto(value.photo)
                ? Error.BAD_CAPTURE : Error.NONE;
        if (invalid != Error.NONE) { finish(Verdict.UNAVAILABLE, invalid, null); return; }
        capture = new Capture(value.photo, value.faceCount, value.qualityAccepted);
        enter(Stage.ANALYZING);
        comparisonChip = card.photo.clone(); comparisonLive = capture.photo.clone();
        algorithm.compare(sessionId, comparisonChip, comparisonLive,
                reply(sessionId, Stage.ANALYZING, this::onComparison));
    }

    private void onComparison(Comparison value) {
        if (value == null || value.liveness == null || value.algorithmVersion == null
                || !value.algorithmVersion.matches("[A-Za-z0-9._:/-]{1,96}")) {
            finish(Verdict.UNAVAILABLE, Error.INVALID_COMPARISON, null); return;
        }
        if (value.liveness == Liveness.UNAVAILABLE) {
            finish(Verdict.UNAVAILABLE, Error.INVALID_COMPARISON, value); return;
        }
        if (value.liveness == Liveness.FAILED) {
            finish(Verdict.REJECTED, Error.LIVENESS_FAILED, value); return;
        }
        if (!finite(value.score) || !finite(value.threshold) || value.rule == null) {
            finish(Verdict.UNAVAILABLE, Error.INVALID_COMPARISON, null); return;
        }
        boolean matches = value.rule == ScoreRule.AT_LEAST
                ? value.score >= value.threshold : value.score <= value.threshold;
        finish(matches ? Verdict.PASSED : Verdict.REJECTED,
                matches ? Error.NONE : Error.NO_MATCH, value);
    }

    private void finish(Verdict verdict, Error error, Comparison comparison) {
        stage = Stage.FINISHED; // Invalidate callbacks BEFORE calling adapter cancellation.
        String masked = card == null ? "—" : "**************" + card.number.substring(14);
        result = new Record(sessionId, clock.wallMillis(), mode, masked,
                card != null && (capture != null || currentFrameObserved)
                        ? (mode == Mode.TEST ? "TEST_FIXTURE_PAIR" : "CURRENT_CHIP_TO_CURRENT_CAPTURE")
                        : "NONE", verdict, error,
                comparison == null ? Liveness.UNAVAILABLE : comparison.liveness,
                comparison == null || !finite(comparison.score) ? null : comparison.score,
                comparison == null || !finite(comparison.threshold) ? null : comparison.threshold,
                comparison == null ? null : comparison.rule,
                comparison == null ? "UNCONNECTED" : comparison.algorithmVersion,pinnedRoster==null?"":pinnedRoster.batchId,membership);
        try { device.cancel(sessionId); } catch (RuntimeException | LinkageError ignored) { /* Already invalid. */ }
        try { if(frames==null)algorithm.cancel(sessionId);else frames.cancel(sessionId); } catch (RuntimeException | LinkageError ignored) { /* Already invalid. */ }
        erasePhotos();
        persist();
    }

    private void persist() {
        try { store.save(result); saveState = SaveState.SAVED; }
        catch (IOException | RuntimeException ex) { saveState = SaveState.FAILED; }
    }
    private void requireSaved() {
        if (saveState == SaveState.FAILED) {
            throw new IllegalStateException("Retry saving or explicitly clear test data before resetting");
        }
    }
    private void reset() {
        erasePhotos(); card = null; capture = null; result = null;
        sessionId = ""; stage = Stage.IDLE; saveState = SaveState.NONE;currentFrameObserved=false;
        pinnedRoster=null;athlete=null;membership=rosterProvider==null?Membership.LEGACY_NOT_EVALUATED:Membership.NO_ROSTER;
    }
    private void erasePhotos() {
        if (card != null) card.erase();
        if (capture != null) capture.erase();
        if (comparisonChip != null) Arrays.fill(comparisonChip, (byte) 0);
        if (comparisonLive != null) Arrays.fill(comparisonLive, (byte) 0);
        comparisonChip = null; comparisonLive = null;
    }
    private void enter(Stage next) { stage = next; deadline = clock.elapsedMillis() + stageTimeoutMillis; }
    private boolean active() { return stage == Stage.READING || stage == Stage.CAPTURING || stage == Stage.ANALYZING; }
    private static boolean validPhoto(byte[] photo) { return photo != null && photo.length > 0 && photo.length <= 8 * 1024 * 1024; }
    private static boolean finite(Double value) { return value != null && !value.isNaN() && !value.isInfinite(); }
    private static void discard(Object value) {
        if(value instanceof Card)((Card)value).close();
        else if(value instanceof Capture)((Capture)value).close();
    }
}
