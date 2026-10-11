package com.triathlon.prep.cefk.hardware;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One outstanding session; no SDK call until read() and an explicit configured factory. */
public final class CefkCardReader implements AutoCloseable {
    public enum Error { UNCONFIGURED, CLOSED, BUSY, INVALID_CARD, MISSING_PHOTO,
        INIT_FAILED, READ_FAILED, SDK_EXCEPTION, TIMEOUT }
    public interface Reply { void card(String sessionId, Card card); void failed(String sessionId, Error error); }
    public interface Factory { Backend create() throws Exception; }
    public interface Backend {
        void initialize(Events events) throws Exception;
        void read(Events events) throws Exception;
        void close() throws Exception;
    }
    public interface Events { void initialized(); void card(Card card); void failed(Error error); }
    /** Owns a PNG copy. The receiver must close it, even when its session has expired. */
    public static final class Card implements AutoCloseable {
        private String name, number;
        private byte[] png;
        public Card(String name, String number, byte[] png) {
            if (name == null || name.trim().isEmpty() || name.length() > 128
                    || number == null || !number.matches("[0-9]{17}[0-9Xx]"))
                throw new IllegalArgumentException("Invalid card fields");
            if (png == null || png.length == 0 || png.length > 8 * 1024 * 1024)
                throw new IllegalArgumentException("Invalid chip photo");
            this.name = name; this.number = number; this.png = png.clone();
        }
        public synchronized String name() { requireOpen(); return name; }
        public synchronized String number() { requireOpen(); return number; }
        public synchronized byte[] photoPng() { requireOpen(); return png.clone(); }
        private void requireOpen() { if (png == null) throw new IllegalStateException("Card closed"); }
        @Override public synchronized void close() {
            if (png != null) Arrays.fill(png, (byte) 0);
            png = null; name = null; number = null;
        }
    }
    private final Object lock = new Object();
    private final Factory factory;
    private final long timeoutMillis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cefk-card"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cefk-card-deadline"); t.setDaemon(true); return t;
    });
    private Job current;
    private boolean closed, busy, poisoned;
    private static final class Job {
        final String id; final Reply reply;
        Backend backend; ScheduledFuture<?> deadline; boolean started;
        Job(String id, Reply reply) { this.id = id; this.reply = reply; }
    }
    public CefkCardReader(Factory factory, long timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 300_000) throw new IllegalArgumentException("Invalid timeout");
        this.factory = factory; this.timeoutMillis = timeoutMillis;
    }
    public void read(String sessionId, Reply reply) {
        if (reply == null) throw new IllegalArgumentException("Reply required");
        Job job = new Job(sessionId, reply); Error reject = null;
        synchronized (lock) {
            if (closed) reject = Error.CLOSED;
            else if (factory == null) reject = Error.UNCONFIGURED;
            else if (sessionId == null || sessionId.trim().isEmpty()) reject = Error.INVALID_CARD;
            else if (poisoned) reject = Error.SDK_EXCEPTION;
            else if (busy) reject = Error.BUSY;
            else {
                current = job; busy = true;
                job.deadline = timer.schedule(() -> finish(job, null, Error.TIMEOUT), timeoutMillis, TimeUnit.MILLISECONDS);
                worker.execute(() -> begin(job));
            }
        }
        if (reject != null) reply.failed(sessionId, reject);
    }
    private boolean active(Job job) { synchronized (lock) { return !closed && current == job; } }
    private Events events(Job job) {
        return new Events() {
            @Override public void initialized() {
                synchronized (lock) {
                    if (!active(job) || job.started) return;
                    job.started = true;
                    worker.execute(() -> {
                        if (!active(job)) return;
                        try { job.backend.read(this); }
                        catch (Exception | LinkageError ex) { finish(job, null, Error.SDK_EXCEPTION); }
                    });
                }
            }
            @Override public void card(Card card) {
                if (card == null) finish(job, null, Error.INVALID_CARD);
                else finish(job, card, null);
            }
            @Override public void failed(Error error) { finish(job, null, error == null ? Error.SDK_EXCEPTION : error); }
        };
    }
    private void begin(Job job) {
        if (!active(job)) return;
        try {
            // Creation and destruction share the worker, including cancellation during create().
            job.backend = factory.create();
            if (job.backend == null) { finish(job, null, Error.UNCONFIGURED); return; }
            if (active(job)) job.backend.initialize(events(job));
        } catch (Exception | LinkageError ex) { finish(job, null, Error.SDK_EXCEPTION); }
    }
    private void finish(Job job, Card card, Error error) {
        synchronized (lock) {
            if (closed || current != job) { if (card != null) card.close(); return; }
            current = null;
            if (job.deadline != null) job.deadline.cancel(false);
            worker.execute(() -> release(job));
        }
        // No adapter lock across core callbacks; core must also check session/stage.
        if (error == null) {
            try { job.reply.card(job.id, card); }
            catch (RuntimeException ex) { card.close(); throw ex; }
        } else job.reply.failed(job.id, error);
    }
    private void release(Job job) {
        boolean failed = false;
        if (job.backend != null) {
            try { job.backend.close(); }
            catch (Exception | LinkageError ex) { failed = true; }
        }
        synchronized (lock) { poisoned |= failed; busy = false; }
    }
    /** Suppresses callbacks; never promises to interrupt an undocumented native operation. */
    public void cancel(String sessionId) {
        synchronized (lock) {
            if (current == null || !current.id.equals(sessionId)) return;
            Job job = current; current = null;
            if (job.deadline != null) job.deadline.cancel(false);
            worker.execute(() -> release(job));
        }
    }
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (current != null) {
                Job job = current; current = null;
                if (job.deadline != null) job.deadline.cancel(false);
                worker.execute(() -> release(job));
            }
            worker.shutdown(); timer.shutdownNow();
        }
    }
}
