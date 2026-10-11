package com.triathlon.prep;

/** One capture request, sensor timestamps ONLY. Used on the Camera2 handler; no wall-clock comparison. */
final class YuvRequestStamp {
    final long ticket;
    private long startedTimestamp, completedTimestamp;
    private long startedFrame = -1, completedFrame = -1;
    YuvRequestStamp(long ticket) { if (ticket <= 0) throw new IllegalArgumentException("Ticket required"); this.ticket = ticket; }
    boolean started(long tag, long timestamp, long frame) {
        if (tag != ticket) return false;
        if (timestamp <= 0 || frame < 0 || (startedTimestamp != 0 && (timestamp != startedTimestamp || frame != startedFrame)))
            throw new IllegalArgumentException("Invalid/changed capture start");
        startedTimestamp = timestamp; startedFrame = frame; validate(); return true;
    }
    boolean completed(long tag, Long timestamp, long frame) {
        if (tag != ticket) return false;
        if (timestamp == null || timestamp <= 0 || frame < 0
                || (completedTimestamp != 0 && (timestamp != completedTimestamp || frame != completedFrame)))
            throw new IllegalArgumentException("Missing/changed sensor result");
        completedTimestamp = timestamp; completedFrame = frame; validate(); return true;
    }
    private void validate() {
        if (startedTimestamp != 0 && completedTimestamp != 0
                && (startedTimestamp != completedTimestamp || startedFrame != completedFrame))
            throw new IllegalArgumentException("Capture start/result mismatch");
    }
    boolean ready() { return startedTimestamp > 0 && completedTimestamp == startedTimestamp && startedFrame == completedFrame; }
    boolean matches(long timestamp) { return ready() && timestamp > 0 && timestamp == startedTimestamp; }
    long timestamp() { if (!ready()) throw new IllegalStateException("Incomplete request metadata"); return startedTimestamp; }
}
