package com.triathlon.prep.intake;

import com.triathlon.prep.core.Verification;
import java.util.UUID;
import java.util.concurrent.*;

/** Idle acquisition outside Verification. One source owner, no card queue across business sessions. */
public final class AutoCardIntake implements AutoCloseable {
    public enum State { PAUSED, STARTING, READY, ADMITTING, BUSY, BLOCKED_SAME_CARD, REVERIFYING, FAULT, CLOSED }
    public enum Error { NONE, UNCONFIGURED, WRONG_MODE, INIT_FAILED, SOURCE_FAILED, SDK_EXCEPTION,
        INIT_TIMEOUT, FENCE_FAILED, RELEASE_FAILED, HOST_FAILED }
    public interface Callback { void initialized(); void card(IntakeCard card); void failed(Error error); }
    public interface Source {
        Verification.Mode mode();
        void initialize(Callback callback) throws Exception;
        /** Returns promptly and delivers asynchronous card events until close(). */
        void read(Callback callback) throws Exception;
        void close() throws Exception;
    }
    public interface SourceFactory { Source create() throws Exception; }
    public interface Listener {
        /** Off UI thread. Post to Host; admission remains invalidatable until its card is taken. */
        void admitted(Admission admission);
        void changed(Snapshot snapshot);
    }
    public static final class Snapshot {
        public final State state; public final Error error; public final Verification.Mode mode;
        public final String admissionId, coreSessionId;
        private Snapshot(State state, Error error, Verification.Mode mode, String id, String coreId) {
            this.state = state; this.error = error; this.mode = mode; admissionId = id; coreSessionId = coreId;
        }
    }
    public static final class Admission implements AutoCloseable {
        public final String id; public final Verification.Mode mode;
        private final AutoCardIntake owner; private IntakeCard card; private String coreSessionId;
        private Admission(AutoCardIntake owner, IntakeCard card) {
            this.owner = owner; this.card = card; mode = card.mode; id = UUID.randomUUID().toString();
        }
        /** Ownership transfer once, only while current and foreground. Do not retain for later sessions. */
        public IntakeCard takeCard() {
            synchronized (owner.lock) {
                if (owner.closed || !owner.foreground || owner.admission != this || owner.state != State.BUSY) return null;
                IntakeCard value = card; card = null; return value;
            }
        }
        boolean bindCoreSession(String id) {
            synchronized (owner.lock) {
                if (owner.closed || !owner.foreground || owner.admission != this || owner.state != State.BUSY
                        || card != null || coreSessionId != null || id == null || id.trim().isEmpty()) return false;
                coreSessionId = id; return true;
            }
        }
        @Override public void close() {
            synchronized (owner.lock) { if (card != null) card.close(); card = null; }
        }
    }
    private final Object lock = new Object();
    private final Verification.Mode mode;
    private final SourceFactory factory;
    private final CardAdmissionFence fence;
    private final Listener listener;
    private final long initializationTimeoutMillis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-card-intake"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auto-card-init-deadline"); t.setDaemon(true); return t;
    });
    private State state = State.PAUSED;
    private Error error = Error.NONE;
    private boolean foreground, closed, poisoned, initializationQueued;
    private long generation;
    private Source source;
    private Admission admission;
    private ScheduledFuture<?> deadline;
    public AutoCardIntake(Verification.Mode mode, SourceFactory factory, CardAdmissionFence fence,
            Listener listener, long initializationTimeoutMillis) {
        if (mode == null || fence == null || listener == null || initializationTimeoutMillis < 1
                || initializationTimeoutMillis > 300_000) throw new IllegalArgumentException("Explicit intake configuration required");
        this.mode = mode; this.factory = factory; this.fence = fence; this.listener = listener;
        this.initializationTimeoutMillis = initializationTimeoutMillis;
    }
    public Snapshot snapshot() {
        synchronized (lock) { return new Snapshot(state, error, mode, admission == null ? "" : admission.id,
                admission == null || admission.coreSessionId == null ? "" : admission.coreSessionId); }
    }
    /** Foreground transition, not a polling retry. FAULT remains stopped pending explicit diagnosis. */
    public boolean start() {
        synchronized (lock) {
            if (closed || poisoned || state == State.FAULT || state != State.PAUSED) return false;
            foreground = true; scheduleOpenLocked();
        }
        publish(); return true;
    }
    private void scheduleOpenLocked() {
        state = State.STARTING; error = Error.NONE; initializationQueued = false;
        final long epoch = ++generation;
        cancelDeadlineLocked();
        deadline = timer.schedule(() -> fail(epoch, Error.INIT_TIMEOUT), initializationTimeoutMillis, TimeUnit.MILLISECONDS);
        worker.execute(() -> open(epoch));
    }
    private boolean active(long epoch) { return !closed && foreground && generation == epoch; }
    private void open(long epoch) {
        synchronized (lock) { if (!active(epoch) || state != State.STARTING || poisoned) return; }
        Source created = null;
        try {
            if (factory == null) { fail(epoch, Error.UNCONFIGURED); return; }
            created = factory.create();
            if (created == null) { fail(epoch, Error.UNCONFIGURED); return; }
            boolean rejected;
            synchronized (lock) { rejected = !active(epoch) || state != State.STARTING; if (!rejected) source = created; }
            if (rejected) { release(created); return; }
            if (created.mode() != mode) { fail(epoch, Error.WRONG_MODE); return; }
            created.initialize(callback(epoch));
        } catch (Exception | LinkageError ex) { fail(epoch, Error.SDK_EXCEPTION); }
    }
    private Callback callback(final long epoch) {
        return new Callback() {
            @Override public void initialized() {
                synchronized (lock) {
                    if (!active(epoch) || state != State.STARTING || initializationQueued) return;
                    initializationQueued = true;
                    worker.execute(() -> read(epoch, this));
                }
            }
            @Override public void card(IntakeCard card) {
                if (card == null) { fail(epoch, Error.SOURCE_FAILED); return; }
                boolean wrongMode;
                synchronized (lock) {
                    if (!active(epoch) || (state != State.READY && state != State.BLOCKED_SAME_CARD)) { card.close(); return; }
                    wrongMode = card.mode != mode;
                    if (!wrongMode) { state = State.ADMITTING; worker.execute(() -> accept(epoch, card)); }
                }
                if (wrongMode) { card.close(); fail(epoch, Error.WRONG_MODE); }
            }
            @Override public void failed(Error error) { fail(epoch, error == null || error == Error.NONE ? Error.SOURCE_FAILED : error); }
        };
    }
    private void read(long epoch, Callback callback) {
        Source current;
        synchronized (lock) {
            if (!active(epoch) || state != State.STARTING) return;
            current = source; state = State.READY; cancelDeadlineLocked();
        }
        try { current.read(callback); }
        catch (Exception | LinkageError ex) { fail(epoch, Error.SDK_EXCEPTION); }
        publish();
    }
    private void accept(long epoch, IntakeCard card) {
        try {
            synchronized (lock) { if (!active(epoch) || state != State.ADMITTING) return; }
            CardAdmissionFence.Result result;
            try { result = fence.admit(mode, card.number()); }
            catch (Exception | LinkageError ex) { result = CardAdmissionFence.Result.FAILED; }
            synchronized (lock) { if (!active(epoch) || state != State.ADMITTING) return; }
            if (result == CardAdmissionFence.Result.SAME_CARD) {
                synchronized (lock) { if (active(epoch)) state = State.BLOCKED_SAME_CARD; }
                publish(); return;
            }
            if (result != CardAdmissionFence.Result.ADMITTED) { fail(epoch, Error.FENCE_FAILED); return; }
            // Reservation is durable first. Release must finish before Host may open a core session.
            if (!releaseSource()) return;
            Admission granted; final long businessEpoch;
            synchronized (lock) {
                if (!active(epoch) || state != State.ADMITTING) return;
                if (admission != null) admission.close();
                granted = new Admission(this, card); card = null; admission = granted; state = State.BUSY;
                businessEpoch = ++generation; // All source callbacks become stale before Host starts.
            }
            publish();
            try { listener.admitted(granted); }
            catch (RuntimeException | LinkageError ex) { granted.close(); fail(businessEpoch, Error.HOST_FAILED); }
        } finally { if (card != null) card.close(); }
    }
    /** Host supplies CURRENT admission's save/print gates; stale callbacks have no effect. */
    public void businessState(String admissionId, String coreSessionId, boolean saved, boolean printingInFlight) {
        synchronized (lock) {
            if (closed || !foreground || admission == null || !admission.id.equals(admissionId)
                    || admission.coreSessionId == null || !admission.coreSessionId.equals(coreSessionId) || state == State.FAULT) return;
            if (!saved || printingInFlight) {
                if (state == State.READY || state == State.BLOCKED_SAME_CARD || state == State.STARTING || state == State.ADMITTING) {
                    ++generation; cancelDeadlineLocked(); state = State.BUSY;
                    worker.execute(this::releaseSource);
                }
            } else if (state == State.BUSY) scheduleOpenLocked();
        }
        publish();
    }
    /** Staff request only. Request acceptance is not confirmation that the fence commit succeeded. */
    public boolean explicitReverify() {
        final long epoch;
        synchronized (lock) {
            if (closed || !foreground || (state != State.READY && state != State.BLOCKED_SAME_CARD)) return false;
            epoch = ++generation; state = State.REVERIFYING; cancelDeadlineLocked();
            worker.execute(() -> {
                if (!releaseSource()) return;
                synchronized (lock) { if (!active(epoch) || state != State.REVERIFYING) return; }
                boolean reset;
                try { reset = fence.explicitReverify(mode); }
                catch (Exception | LinkageError ex) { reset = false; }
                if (!reset) { fail(epoch, Error.FENCE_FAILED); return; }
                synchronized (lock) { if (active(epoch) && state == State.REVERIFYING) scheduleOpenLocked(); }
                publish();
            });
        }
        publish(); return true;
    }
    public void pause() {
        synchronized (lock) {
            if (closed) return;
            foreground = false; ++generation; cancelDeadlineLocked(); state = State.PAUSED;
            if (admission != null) admission.close(); admission = null;
            worker.execute(this::releaseSource);
        }
        publish();
    }
    private void fail(long epoch, Error reason) {
        synchronized (lock) {
            if (!active(epoch) || state == State.FAULT) return;
            error = reason; state = State.FAULT; ++generation; cancelDeadlineLocked();
            if (admission != null) admission.close();
            worker.execute(this::releaseSource);
        }
        publish();
    }
    private boolean releaseSource() {
        Source old;
        synchronized (lock) { old = source; source = null; }
        return old == null || release(old);
    }
    private boolean release(Source old) {
        try { old.close(); return true; }
        catch (Exception | LinkageError ex) {
            synchronized (lock) {
                poisoned = true; error = Error.RELEASE_FAILED; ++generation; cancelDeadlineLocked();
                if (!closed) state = State.FAULT;
            }
            publish(); return false;
        }
    }
    private void cancelDeadlineLocked() { if (deadline != null) deadline.cancel(false); deadline = null; }
    private void publish() {
        Snapshot value = snapshot();
        try { listener.changed(value); }
        catch (RuntimeException | LinkageError ignored) { /* State remains authoritative; no retry/event replay. */ }
    }
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true; foreground = false; ++generation; cancelDeadlineLocked(); state = State.CLOSED;
            if (admission != null) admission.close(); admission = null;
            worker.execute(this::releaseSource); worker.shutdown(); timer.shutdownNow();
        }
        publish();
    }
}
