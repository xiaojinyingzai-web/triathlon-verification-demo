package com.triathlon.prep.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/** Private, versioned snapshot. No document numbers, names, image bytes or features are persisted. */
public final class FileRecordStore implements Verification.RecordStore {
    private static final int MAGIC = 0x54505331;
    private static final int LIMIT = 1000;
    private final File file, backup, pending;

    // ponytail: one owner per app process and 1000 demo records; move to SQLite for concurrency/large history.
    public FileRecordStore(File privateDirectory) throws IOException {
        if ((!privateDirectory.exists() && !privateDirectory.mkdirs()) || !privateDirectory.isDirectory()) {
            throw new IOException("Private log directory unavailable");
        }
        file = new File(privateDirectory, "records.bin");
        backup = new File(privateDirectory, "records.bin.bak");
        pending = new File(privateDirectory, "records.bin.new");
    }

    @Override public synchronized void save(Verification.Record record) throws IOException {
        validate(record);
        List<Verification.Record> records = list();
        records.removeIf(item -> item.sessionId.equals(record.sessionId));
        if (records.size() >= LIMIT) throw new IOException("Demo log limit reached; clear test records");
        records.add(record);
        commit(records);
    }

    @Override public synchronized List<Verification.Record> list() throws IOException {
        recover();
        List<Verification.Record> records = new ArrayList<>();
        if (!file.exists()) return records;
        if (file.length() > 2 * 1024 * 1024) throw new IOException("Invalid log size");
        CRC32 checksum = new CRC32();
        try (DataInputStream input = new DataInputStream(new CheckedInputStream(new FileInputStream(file), checksum))) {
            if (input.readInt() != MAGIC) throw new IOException("Unknown log format");
            int version=input.readInt();if(version!=2&&version!=3)throw new IOException("Unknown log format");
            int count = input.readInt();
            if (count < 0 || count > LIMIT) throw new IOException("Invalid log count");
            for (int i = 0; i < count; i++) {
                Verification.Record record = new Verification.Record(input.readUTF(), input.readLong(),
                        Verification.Mode.valueOf(input.readUTF()), input.readUTF(), input.readUTF(),
                        Verification.Verdict.valueOf(input.readUTF()), Verification.Error.valueOf(input.readUTF()),
                        Verification.Liveness.valueOf(input.readUTF()), readNumber(input), readNumber(input),
                        input.readBoolean() ? Verification.ScoreRule.valueOf(input.readUTF()) : null, input.readUTF());
                if(version==3)record=new Verification.Record(record.sessionId,record.timestamp,record.mode,record.maskedNumber,
                        record.comparisonSource,record.verdict,record.error,record.liveness,record.score,record.threshold,record.rule,record.algorithmVersion,
                        input.readUTF(),Verification.Membership.valueOf(input.readUTF()));
                validate(record);
                records.add(record);
            }
            long actualChecksum = checksum.getValue();
            if (input.readLong() != actualChecksum) throw new IOException("Log checksum mismatch");
            if (input.read() != -1) throw new IOException("Unexpected log data");
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid log data", ex); }
        return records;
    }

    @Override public synchronized void clear() throws IOException { recover(); commit(new ArrayList<>()); }

    private void recover() throws IOException {
        // An interrupted replace restores the last committed snapshot; corruption never becomes empty history.
        if (backup.exists()) {
            if (file.exists() && !file.delete()) throw new IOException("Cannot restore log backup");
            if (!backup.renameTo(file)) throw new IOException("Cannot restore log backup");
        }
        if (pending.exists() && !pending.delete()) throw new IOException("Cannot remove interrupted log write");
    }

    private void commit(List<Verification.Record> records) throws IOException {
        CRC32 checksum = new CRC32();
        try (FileOutputStream stream = new FileOutputStream(pending);
             DataOutputStream output = new DataOutputStream(new CheckedOutputStream(stream, checksum))) {
            output.writeInt(MAGIC); output.writeInt(3); output.writeInt(records.size());
            for (Verification.Record record : records) {
                output.writeUTF(record.sessionId); output.writeLong(record.timestamp);
                output.writeUTF(record.mode.name()); output.writeUTF(record.maskedNumber);
                output.writeUTF(record.comparisonSource); output.writeUTF(record.verdict.name());
                output.writeUTF(record.error.name()); output.writeUTF(record.liveness.name());
                writeNumber(output, record.score); writeNumber(output, record.threshold);
                output.writeBoolean(record.rule != null);
                if (record.rule != null) output.writeUTF(record.rule.name());
                output.writeUTF(record.algorithmVersion);
                output.writeUTF(record.rosterBatchId);output.writeUTF(record.membership.name());
            }
            output.writeLong(checksum.getValue());
            output.flush(); stream.getFD().sync();
        }
        if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot preserve previous log");
        if (!pending.renameTo(file)) {
            if (backup.exists() && !backup.renameTo(file)) throw new IOException("Log replace and restore failed");
            throw new IOException("Log replace failed");
        }
        if (backup.exists() && !backup.delete()) throw new IOException("Log backup cleanup failed");
    }

    private static void validate(Verification.Record record) throws IOException {
        if (record == null || record.sessionId == null
                || !record.sessionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || record.mode == null || record.verdict == null || record.error == null || record.liveness == null
                || record.maskedNumber == null || !record.maskedNumber.matches("(?:—|\\*{14}[0-9Xx]{4})")
                || record.comparisonSource == null
                || !record.comparisonSource.matches("NONE|TEST_FIXTURE_PAIR|CURRENT_CHIP_TO_CURRENT_CAPTURE")
                || record.algorithmVersion == null || !record.algorithmVersion.matches("[A-Za-z0-9._:/-]{1,96}")
                || record.membership==null || record.rosterBatchId==null
                || !record.rosterBatchId.matches("(?:|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
                || (record.membership==Verification.Membership.MATCHED&&record.rosterBatchId.isEmpty())
                || (record.score != null && !Double.isFinite(record.score))
                || (record.threshold != null && !Double.isFinite(record.threshold))) {
            throw new IOException("Unsafe or malformed record");
        }
    }
    private static void writeNumber(DataOutputStream output, Double number) throws IOException {
        output.writeBoolean(number != null); if (number != null) output.writeDouble(number);
    }
    private static Double readNumber(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readDouble() : null;
    }
}
