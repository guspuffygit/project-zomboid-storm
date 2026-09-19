package io.pzstorm.storm.bullet.trace;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Streams a trace file and rebuilds the per-thread call trees. Top-level calls are delivered in the
 * order they began (global sequence), each only once its whole subtree has completed, so a replay
 * sees exactly the order in which the game entered the library. Traces cut short by a crash are
 * read up to the last complete event; unfinished calls are counted, not delivered.
 */
public final class TraceReader implements Closeable {

    private final DataInputStream in;
    private final TraceHeader header;
    private final Sig[] table;

    private final Map<Long, Deque<Object>> stacks = new HashMap<>();
    private final Map<Long, String> threadNames = new LinkedHashMap<>();
    private final TreeMap<Long, CallNode> pendingRoots = new TreeMap<>();
    private final List<String> notes = new ArrayList<>();
    private final ArrayDeque<CallNode> ready = new ArrayDeque<>();
    private boolean finished;
    private long lastSeq = -1;
    private boolean cleanEnd;
    private boolean truncated;
    private int overlappingRoots;
    private int openRoots;
    private long events;
    private String problem;

    public TraceReader(InputStream is) throws IOException {
        this.in = new DataInputStream(new BufferedInputStream(is, 1 << 16));
        if (in.readInt() != TraceFormat.MAGIC) {
            throw new IOException("not a PZBT trace (bad magic)");
        }
        int version = in.readUnsignedShort();
        if (version != TraceFormat.VERSION) {
            throw new IOException("unsupported trace version " + version);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        int metaCount = in.readUnsignedShort();
        for (int i = 0; i < metaCount; i++) {
            meta.put(readStr(), readStr());
        }
        int n = in.readUnsignedShort();
        table = new Sig[n];
        for (int i = 0; i < n; i++) {
            boolean upcall = in.readUnsignedByte() == 1;
            String name = readStr();
            String desc = readStr();
            Sig known = upcall ? BulletApi.upcallSig(name, desc) : BulletApi.nativeSig(name, desc);
            table[i] = known != null ? known : new Sig(upcall, name, desc);
        }
        header = new TraceHeader(version, meta, List.of(table));
    }

    public static TraceReader open(Path path) throws IOException {
        InputStream is = Files.newInputStream(path);
        if (path.getFileName().toString().endsWith(".gz")) {
            is = new GZIPInputStream(is, 1 << 16);
        }
        return new TraceReader(is);
    }

    /** Reads a whole trace into memory. */
    public static Trace readAll(Path path) throws IOException {
        try (TraceReader r = open(path)) {
            List<CallNode> roots = new ArrayList<>();
            r.forEachRoot(roots::add);
            return new Trace(r.header, roots, r.stats());
        }
    }

    public TraceHeader header() {
        return header;
    }

    /** Delivers every completed top-level call in begin order. */
    public void forEachRoot(Consumer<CallNode> sink) throws IOException {
        CallNode c;
        while ((c = nextRoot()) != null) {
            sink.accept(c);
        }
    }

    /** The next completed top-level call in begin order, or null at the end of the trace. */
    public CallNode nextRoot() throws IOException {
        while (ready.isEmpty() && !finished) {
            int tag;
            try {
                tag = in.read();
                if (tag < 0) {
                    truncated = true;
                    finish();
                } else if (!readEvent(tag, ready::add)) {
                    finish();
                }
            } catch (EOFException e) {
                truncated = true;
                finish();
            }
        }
        return ready.poll();
    }

    private void finish() {
        finished = true;
        // anything still pending never completed (crash / kill mid-call)
        for (CallNode c : pendingRoots.values()) {
            if (c.completed) {
                ready.add(c);
            } else {
                openRoots++;
            }
        }
        pendingRoots.clear();
    }

    public TraceStats stats() {
        return new TraceStats(
                events,
                cleanEnd,
                truncated,
                openRoots,
                overlappingRoots,
                Map.copyOf(threadNames),
                List.copyOf(notes),
                problem);
    }

    private boolean readEvent(int tag, Consumer<CallNode> sink) throws IOException {
        long seq = in.readLong();
        long tid = in.readLong();
        if (seq <= lastSeq && tag != TraceFormat.END) {
            problem = "sequence went backwards at " + seq + " after " + lastSeq;
        }
        lastSeq = seq;
        events++;
        Deque<Object> stack = stacks.computeIfAbsent(tid, k -> new ArrayDeque<>());
        switch (tag) {
            case TraceFormat.THREAD -> threadNames.put(tid, readStr());
            case TraceFormat.NOTE -> notes.add(readStr());
            case TraceFormat.END -> {
                cleanEnd = true;
                return false;
            }
            case TraceFormat.CALL_BEGIN -> {
                Sig sig = table[in.readUnsignedShort()];
                Object[] args = readValues(sig.params);
                CallNode c = new CallNode(seq, tid, sig, args);
                Object top = stack.peek();
                if (top == null) {
                    if (pendingRoots.values().stream().anyMatch(r -> !r.completed)) {
                        overlappingRoots++;
                    }
                    pendingRoots.put(seq, c);
                } else if (top instanceof UpcallNode u) {
                    u.calls.add(c);
                } else {
                    throw new IOException(
                            "call " + c + " nested directly in call " + top + " without upcall");
                }
                stack.push(c);
            }
            case TraceFormat.CALL_END -> {
                Sig sig = table[in.readUnsignedShort()];
                if (!(stack.peek() instanceof CallNode c) || c.sig != sig) {
                    throw new IOException(
                            "CALL_END " + sig + " at #" + seq + " does not match " + stack.peek());
                }
                stack.pop();
                for (int i = 0; i < sig.params.length; i++) {
                    if (sig.params[i].isMutable()) {
                        c.argsAfter[i] = readValue(sig.params[i]);
                    }
                }
                readOutcome(sig, c);
                c.completed = true;
                c.endSeq = seq;
                if (stack.isEmpty()) {
                    drainRoots(sink);
                }
            }
            case TraceFormat.UPCALL_BEGIN -> {
                Sig sig = table[in.readUnsignedShort()];
                Object[] args = readValues(sig.params);
                UpcallNode u = new UpcallNode(seq, tid, sig, args);
                if (!(stack.peek() instanceof CallNode c)) {
                    throw new IOException("upcall " + u + " outside of a native call");
                }
                c.upcalls.add(u);
                stack.push(u);
            }
            case TraceFormat.UPCALL_END -> {
                Sig sig = table[in.readUnsignedShort()];
                if (!(stack.peek() instanceof UpcallNode u) || u.sig != sig) {
                    throw new IOException(
                            "UPCALL_END "
                                    + sig
                                    + " at #"
                                    + seq
                                    + " does not match "
                                    + stack.peek());
                }
                stack.pop();
                if (in.readUnsignedByte() == TraceFormat.OUTCOME_THREW) {
                    u.thrownClass = readStr();
                    u.thrownMessage = readStr();
                } else if (sig.ret != ValueKind.VOID) {
                    u.ret = readValue(sig.ret);
                }
                u.completed = true;
            }
            default -> throw new IOException("unknown event tag " + tag + " at #" + seq);
        }
        return true;
    }

    private void drainRoots(Consumer<CallNode> sink) {
        while (!pendingRoots.isEmpty() && pendingRoots.firstEntry().getValue().completed) {
            sink.accept(pendingRoots.pollFirstEntry().getValue());
        }
    }

    private void readOutcome(Sig sig, CallNode c) throws IOException {
        if (in.readUnsignedByte() == TraceFormat.OUTCOME_THREW) {
            c.thrownClass = readStr();
            c.thrownMessage = readStr();
        } else if (sig.ret != ValueKind.VOID) {
            c.ret = readValue(sig.ret);
        }
    }

    private Object[] readValues(ValueKind[] kinds) throws IOException {
        Object[] v = new Object[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            v[i] = readValue(kinds[i]);
        }
        return v;
    }

    private Object readValue(ValueKind kind) throws IOException {
        return switch (kind) {
            case BOOLEAN -> in.readUnsignedByte() != 0;
            case INT -> in.readInt();
            case FLOAT -> Float.intBitsToFloat(in.readInt());
            case STRING -> readStr();
            case FLOAT_ARRAY -> {
                int n = in.readInt();
                if (n < 0) yield null;
                float[] a = new float[n];
                for (int i = 0; i < n; i++) a[i] = Float.intBitsToFloat(in.readInt());
                yield a;
            }
            case INT_ARRAY -> {
                int n = in.readInt();
                if (n < 0) yield null;
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                yield a;
            }
            case BYTE_BUFFER -> {
                int flags = in.readUnsignedByte();
                if ((flags & 1) == 0) yield null;
                int pos = in.readInt();
                int lim = in.readInt();
                int cap = in.readInt();
                byte[] content = new byte[in.readInt()];
                in.readFully(content);
                yield new BufferSnapshot(
                        (flags & 2) != 0, (flags & 4) != 0, pos, lim, cap, content);
            }
            case VOID -> null;
        };
    }

    private String readStr() throws IOException {
        int n = in.readInt();
        if (n < 0) {
            return null;
        }
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
