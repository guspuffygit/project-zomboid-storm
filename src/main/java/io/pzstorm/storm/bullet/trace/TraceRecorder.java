package io.pzstorm.storm.bullet.trace;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Records native calls and upcalls around an invoker. Used by the in-game recorder patch and by
 * {@link RecordingBackend}. Fails soft: the first I/O error is reported once through {@code
 * errorSink}, recording stops, and every call keeps going straight to its target.
 */
public final class TraceRecorder {

    /** Performs the real call with the (live) arguments. */
    @FunctionalInterface
    public interface Invoker {
        Object invoke(Object[] args) throws Throwable;
    }

    private static final class Frames {
        /** Innermost frame kinds of this thread, true = native call; bit stack, depth-indexed. */
        long bits;

        int depth;
    }

    private final TraceWriter writer;
    private final Consumer<String> errorSink;
    private final ThreadLocal<Frames> frames = ThreadLocal.withInitial(Frames::new);
    private volatile boolean enabled = true;

    public TraceRecorder(TraceWriter writer, Consumer<String> errorSink) {
        this.writer = writer;
        this.errorSink = errorSink;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public TraceWriter writer() {
        return writer;
    }

    /**
     * True when the innermost frame of the current thread is a native call — i.e. a callback now
     * running was invoked by the library, not by ordinary Java code inside an upcall.
     */
    public boolean insideNativeCall() {
        Frames f = frames.get();
        return f.depth > 0 && ((f.bits >>> ((f.depth - 1) & 63)) & 1) != 0;
    }

    public Object call(Sig sig, Object[] args, Invoker invoker) throws Throwable {
        if (!enabled) {
            return invoker.invoke(args);
        }
        Thread t = Thread.currentThread();
        long tid = t.threadId();
        Object[] before = snapshot(sig, args);
        try {
            writer.callBegin(tid, t.getName(), sig, before);
        } catch (IOException | RuntimeException e) {
            fail(e);
            return invoker.invoke(args);
        }
        Frames f = frames.get();
        push(f, true);
        Object ret;
        try {
            ret = invoker.invoke(args);
        } catch (Throwable thrown) {
            pop(f);
            end(tid, sig, args, null, thrown);
            throw thrown;
        }
        pop(f);
        end(tid, sig, args, ret, null);
        return ret;
    }

    public Object upcall(Sig sig, Object[] args, Invoker invoker) throws Throwable {
        if (!enabled) {
            return invoker.invoke(args);
        }
        Thread t = Thread.currentThread();
        long tid = t.threadId();
        try {
            writer.upcallBegin(tid, t.getName(), sig, snapshot(sig, args));
        } catch (IOException | RuntimeException e) {
            fail(e);
            return invoker.invoke(args);
        }
        Frames f = frames.get();
        push(f, false);
        Object ret;
        try {
            ret = invoker.invoke(args);
        } catch (Throwable thrown) {
            pop(f);
            upEnd(tid, sig, null, thrown);
            throw thrown;
        }
        pop(f);
        upEnd(tid, sig, ret, null);
        return ret;
    }

    /**
     * Split form of {@link #upcall} for bytecode advice, which cannot wrap the method body: {@code
     * upcallEnter} returns true when an UPCALL_BEGIN was written and {@link #upcallExit} must
     * follow.
     */
    public boolean upcallEnter(Sig sig, Object[] args) {
        if (!enabled) {
            return false;
        }
        Thread t = Thread.currentThread();
        try {
            writer.upcallBegin(t.threadId(), t.getName(), sig, snapshot(sig, args));
        } catch (IOException | RuntimeException e) {
            fail(e);
            return false;
        }
        push(frames.get(), false);
        return true;
    }

    public void upcallExit(Sig sig, Object ret, Throwable thrown) {
        pop(frames.get());
        upEnd(Thread.currentThread().threadId(), sig, ret, thrown);
    }

    public void note(String text) {
        if (!enabled) {
            return;
        }
        try {
            writer.note(text);
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    public void flush() {
        try {
            writer.flush();
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    public void close() {
        enabled = false;
        try {
            writer.close();
        } catch (IOException | RuntimeException e) {
            errorSink.accept("bullet trace close failed: " + e);
        }
    }

    private void end(long tid, Sig sig, Object[] args, Object ret, Throwable thrown) {
        if (!enabled) {
            return;
        }
        try {
            Object[] after = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                if (sig.params[i].isMutable()) {
                    after[i] = Values.snapshot(sig.params[i], args[i]);
                }
            }
            writer.callEnd(tid, sig, after, Values.snapshot(sig.ret, ret), thrown);
            if (frames.get().depth == 0) {
                writer.maybeFlush(1000);
            }
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    private void upEnd(long tid, Sig sig, Object ret, Throwable thrown) {
        if (!enabled) {
            return;
        }
        try {
            writer.upcallEnd(tid, sig, ret, thrown);
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    private static Object[] snapshot(Sig sig, Object[] args) {
        Object[] s = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            s[i] = Values.snapshot(sig.params[i], args[i]);
        }
        return s;
    }

    private static void push(Frames f, boolean nativeCall) {
        int bit = f.depth & 63;
        f.bits = nativeCall ? f.bits | (1L << bit) : f.bits & ~(1L << bit);
        f.depth++;
    }

    private static void pop(Frames f) {
        if (f.depth > 0) {
            f.depth--;
        }
    }

    private synchronized void fail(Exception e) {
        if (enabled) {
            enabled = false;
            errorSink.accept("bullet trace recording stopped, physics continues untraced: " + e);
            try {
                writer.close();
            } catch (IOException | RuntimeException ignored) {
                // already failing
            }
        }
    }
}
