package io.pzstorm.storm.bullet.trace;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Thread-safe writer of the {@link TraceFormat}. Every event is written under one lock and gets the
 * next global sequence number inside it, so the file never reorders events. Values must already be
 * snapshots (see {@link Values#snapshot}) — the writer does not copy.
 */
public final class TraceWriter implements Closeable {

    private final DataOutputStream out;
    private final List<Sig> table;
    private final Set<Long> namedThreads = new HashSet<>();
    private long seq;
    private boolean closed;
    private long lastFlushNanos = System.nanoTime();

    public TraceWriter(OutputStream os, Map<String, String> metadata) throws IOException {
        this.out = new DataOutputStream(new BufferedOutputStream(os, 1 << 16));
        this.table = BulletApi.ALL;
        out.writeInt(TraceFormat.MAGIC);
        out.writeShort(TraceFormat.VERSION);
        Map<String, String> meta = new LinkedHashMap<>(metadata);
        out.writeShort(meta.size());
        for (Map.Entry<String, String> e : meta.entrySet()) {
            writeStr(e.getKey());
            writeStr(e.getValue());
        }
        out.writeShort(table.size());
        for (Sig s : table) {
            out.writeByte(s.upcall ? 1 : 0);
            writeStr(s.name);
            writeStr(s.descriptor);
        }
        out.flush();
    }

    /** Opens {@code path} for writing; gzip when the name ends in {@code .gz}. */
    public static TraceWriter open(Path path, Map<String, String> metadata) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        OutputStream os = Files.newOutputStream(path);
        if (path.getFileName().toString().endsWith(".gz")) {
            os = new GZIPOutputStream(os, 1 << 16, true);
        }
        return new TraceWriter(os, metadata);
    }

    public synchronized long callBegin(long tid, String threadName, Sig sig, Object[] args)
            throws IOException {
        long s = head(TraceFormat.CALL_BEGIN, tid, threadName);
        out.writeShort(sig.index);
        for (int i = 0; i < sig.params.length; i++) {
            writeValue(sig.params[i], args[i]);
        }
        return s;
    }

    /**
     * @param argsAfter snapshots of the arguments after the call; only mutable ones are written
     * @param ret snapshot of the return value (ignored when {@code thrown != null})
     */
    public synchronized long callEnd(
            long tid, Sig sig, Object[] argsAfter, Object ret, Throwable thrown)
            throws IOException {
        long s = head(TraceFormat.CALL_END, tid, null);
        out.writeShort(sig.index);
        for (int i = 0; i < sig.params.length; i++) {
            if (sig.params[i].isMutable()) {
                writeValue(sig.params[i], argsAfter[i]);
            }
        }
        writeOutcome(sig, ret, thrown);
        return s;
    }

    public synchronized long upcallBegin(long tid, String threadName, Sig sig, Object[] args)
            throws IOException {
        long s = head(TraceFormat.UPCALL_BEGIN, tid, threadName);
        out.writeShort(sig.index);
        for (int i = 0; i < sig.params.length; i++) {
            writeValue(sig.params[i], args[i]);
        }
        return s;
    }

    public synchronized long upcallEnd(long tid, Sig sig, Object ret, Throwable thrown)
            throws IOException {
        long s = head(TraceFormat.UPCALL_END, tid, null);
        out.writeShort(sig.index);
        writeOutcome(sig, ret, thrown);
        return s;
    }

    public synchronized void note(String text) throws IOException {
        head(TraceFormat.NOTE, Thread.currentThread().threadId(), null);
        writeStr(text);
    }

    /** Flushes if more than {@code intervalMillis} passed since the last flush. */
    public synchronized void maybeFlush(long intervalMillis) throws IOException {
        long now = System.nanoTime();
        if (now - lastFlushNanos > intervalMillis * 1_000_000L) {
            lastFlushNanos = now;
            out.flush();
        }
    }

    public synchronized void flush() throws IOException {
        if (!closed) {
            out.flush();
        }
    }

    public synchronized long eventCount() {
        return seq;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.writeByte(TraceFormat.END);
            out.writeLong(seq++);
            out.writeLong(0);
        } finally {
            out.close();
        }
    }

    private long head(int tag, long tid, String threadName) throws IOException {
        if (closed) {
            throw new IOException("trace writer closed");
        }
        if (namedThreads.add(tid)) {
            out.writeByte(TraceFormat.THREAD);
            out.writeLong(seq++);
            out.writeLong(tid);
            writeStr(threadName != null ? threadName : "thread-" + tid);
        }
        long s = seq++;
        out.writeByte(tag);
        out.writeLong(s);
        out.writeLong(tid);
        return s;
    }

    private void writeOutcome(Sig sig, Object ret, Throwable thrown) throws IOException {
        if (thrown != null) {
            out.writeByte(TraceFormat.OUTCOME_THREW);
            writeStr(exceptionClassName(thrown));
            writeStr(thrown.getMessage());
        } else {
            out.writeByte(TraceFormat.OUTCOME_RETURNED);
            if (sig.ret != ValueKind.VOID) {
                writeValue(sig.ret, ret);
            }
        }
    }

    /** The class name a trace records for {@code t}. */
    public static String exceptionClassName(Throwable t) {
        return t instanceof RecordedException r ? r.recordedClassName : t.getClass().getName();
    }

    private void writeStr(String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private void writeValue(ValueKind kind, Object v) throws IOException {
        switch (kind) {
            case BOOLEAN -> out.writeByte(((Boolean) v) ? 1 : 0);
            case INT -> out.writeInt((Integer) v);
            case FLOAT -> out.writeInt(Float.floatToRawIntBits((Float) v));
            case STRING -> writeStr((String) v);
            case FLOAT_ARRAY -> {
                float[] a = (float[]) v;
                if (a == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(a.length);
                    for (float f : a) {
                        out.writeInt(Float.floatToRawIntBits(f));
                    }
                }
            }
            case INT_ARRAY -> {
                int[] a = (int[]) v;
                if (a == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(a.length);
                    for (int x : a) {
                        out.writeInt(x);
                    }
                }
            }
            case BYTE_BUFFER -> {
                BufferSnapshot b = (BufferSnapshot) v;
                if (b == null) {
                    out.writeByte(0);
                    return;
                }
                out.writeByte(1 | (b.direct() ? 2 : 0) | (b.littleEndian() ? 4 : 0));
                out.writeInt(b.position());
                out.writeInt(b.limit());
                out.writeInt(b.capacity());
                out.writeInt(b.content().length);
                out.write(b.content());
            }
            case VOID -> {}
        }
    }

    /**
     * Stand-in for an exception known only by class name and message (replayed traces). {@link
     * #getClass()} cannot be faked, so writers use {@link #recordedClassName}.
     */
    public static final class RecordedException extends RuntimeException {
        public final String recordedClassName;

        public RecordedException(String className, String message) {
            super(message, null, false, false);
            this.recordedClassName = className;
        }
    }
}
