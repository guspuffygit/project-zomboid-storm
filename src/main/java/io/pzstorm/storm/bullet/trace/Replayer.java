package io.pzstorm.storm.bullet.trace;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Replays a recorded trace against a {@link BackendSession} and compares every output bit-exactly.
 *
 * <p>Top-level calls are replayed one at a time in the order the game entered the library (global
 * sequence), on the replaying thread. While a call runs, every upcall the backend makes is matched
 * against the upcalls the recording has for that call, in order: a match has its arguments
 * compared, the native calls Java made inside it are re-issued (recursively, same rules), and the
 * recorded result is returned — or the recorded exception re-thrown as a {@link
 * TraceWriter.RecordedException}. An upcall that does not match is a divergence and is answered
 * with {@link Values#defaultUpcallResult}; recorded upcalls the backend never made are reported as
 * missing when the call returns.
 */
public final class Replayer {

    /**
     * @param maxKept divergences kept in full in the report
     * @param stopAtFirst stop after the first top-level call that diverged
     * @param looseUpcalls upcalls matched leniently: a recorded one the backend does not make, or
     *     an unrecorded one it does make, is skipped/answered by default without a divergence (e.g.
     *     {@code nativeLog} when a port logs differently); matching ones are still compared
     * @param progress receives a line every {@code progressEvery} top-level calls (null = none)
     */
    public record Options(
            int maxKept,
            boolean stopAtFirst,
            Set<Sig> looseUpcalls,
            Consumer<String> progress,
            long progressEvery) {

        public static Options defaults() {
            return new Options(50, false, Set.of(), null, 0);
        }

        public Options withLoose(Set<Sig> loose) {
            return new Options(maxKept, stopAtFirst, loose, progress, progressEvery);
        }

        public Options withStopAtFirst(boolean stop) {
            return new Options(maxKept, stop, looseUpcalls, progress, progressEvery);
        }

        public Options withMaxKept(int max) {
            return new Options(max, stopAtFirst, looseUpcalls, progress, progressEvery);
        }

        public Options withProgress(Consumer<String> sink, long every) {
            return new Options(maxKept, stopAtFirst, looseUpcalls, sink, every);
        }
    }

    /** Expected upcalls of the native call currently running. */
    private static final class Frame {
        final CallNode call;
        final String path;
        int cursor;

        Frame(CallNode call, String path) {
            this.call = call;
            this.path = path;
        }
    }

    private final BackendSession session;
    private final Options options;
    private final ReplayReport report;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private long rootIndex;

    public Replayer(BackendSession session, ReplayReport report, Options options) {
        this.session = session;
        this.report = report;
        this.options = options;
    }

    public ReplayReport report() {
        return report;
    }

    /** Replays every top-level call of the trace. */
    public ReplayReport replay(TraceReader reader) throws IOException {
        session.installUpcalls(new Responder());
        CallNode root;
        while ((root = reader.nextRoot()) != null) {
            if (!replayRoot(root)) {
                report.info(
                        "stopped at first divergence (top-level call #" + (rootIndex - 1) + ")");
                break;
            }
        }
        TraceStats stats = reader.stats();
        if (stats.truncated() || stats.unfinishedRoots() > 0) {
            report.info(
                    "recording was cut short: truncated="
                            + stats.truncated()
                            + ", unfinished top-level calls="
                            + stats.unfinishedRoots());
        }
        if (stats.problem() != null) {
            report.info("trace problem: " + stats.problem());
        }
        return report;
    }

    /** Replays a list of top-level calls (in-memory trace). */
    public ReplayReport replay(List<CallNode> roots) {
        session.installUpcalls(new Responder());
        for (CallNode root : roots) {
            if (!replayRoot(root)) {
                break;
            }
        }
        return report;
    }

    /**
     * @return false when replay should stop
     */
    private boolean replayRoot(CallNode root) {
        long before = report.divergenceCount();
        report.countRoot();
        frames.clear();
        replayCall(root, root.sig.name);
        rootIndex++;
        if (options.progress() != null
                && options.progressEvery() > 0
                && rootIndex % options.progressEvery() == 0) {
            options.progress()
                    .accept(
                            "replayed "
                                    + rootIndex
                                    + " top-level calls, "
                                    + report.divergenceCount()
                                    + " divergences");
        }
        return !(options.stopAtFirst() && report.divergenceCount() > before);
    }

    private void replayCall(CallNode expected, String path) {
        Sig sig = expected.sig;
        report.countCall(sig.name);
        if (sig.method == null) {
            diverge(
                    expected,
                    path,
                    Divergence.Kind.UNSUPPORTED,
                    "native not in BulletBackend: " + sig);
            return;
        }
        Object[] live = new Object[expected.args.length];
        for (int i = 0; i < live.length; i++) {
            live[i] = Values.materialize(sig.params[i], expected.args[i]);
        }
        Frame frame = new Frame(expected, path);
        frames.push(frame);
        Object ret = null;
        Throwable thrown = null;
        try {
            ret = sig.method.invoke(session.bullet(), live);
        } catch (InvocationTargetException e) {
            thrown = e.getCause();
        } catch (IllegalAccessException e) {
            thrown = e;
        } finally {
            frames.pop();
        }
        // recorded upcalls the backend never made
        List<UpcallNode> ups = expected.upcalls;
        for (int i = frame.cursor; i < ups.size(); i++) {
            UpcallNode u = ups.get(i);
            if (!options.looseUpcalls().contains(u.sig)) {
                diverge(
                        expected,
                        u.seq,
                        path + " > upcall " + u.sig.name,
                        Divergence.Kind.MISSING_UPCALL,
                        u.sig.name,
                        "recorded upcall #" + i + " not made: " + u.signatureWithArgs());
            }
        }
        compareOutcome(expected, path, live, ret, thrown);
    }

    private void compareOutcome(
            CallNode expected, String path, Object[] live, Object ret, Throwable thrown) {
        Sig sig = expected.sig;
        String actualClass = thrown == null ? null : TraceWriter.exceptionClassName(thrown);
        String actualMessage = thrown == null ? null : thrown.getMessage();
        if (expected.threw() || thrown != null) {
            if (!java.util.Objects.equals(expected.thrownClass, actualClass)
                    || !java.util.Objects.equals(expected.thrownMessage, actualMessage)) {
                diverge(
                        expected,
                        path,
                        Divergence.Kind.EXCEPTION,
                        "expected "
                                + (expected.threw()
                                        ? expected.thrownClass + ": " + expected.thrownMessage
                                        : "normal return")
                                + " actual "
                                + (thrown != null
                                        ? actualClass + ": " + actualMessage
                                        : "normal return"));
            }
        } else if (sig.ret != ValueKind.VOID && !Values.same(sig.ret, expected.ret, ret)) {
            diverge(
                    expected,
                    path,
                    Divergence.Kind.RETURN,
                    "return "
                            + Values.explain(sig.ret, expected.ret, Values.snapshot(sig.ret, ret)));
        }
        for (int i = 0; i < sig.params.length; i++) {
            if (!sig.params[i].isMutable()) {
                continue;
            }
            Object after = Values.snapshot(sig.params[i], live[i]);
            if (!Values.same(sig.params[i], expected.argsAfter[i], after)) {
                diverge(
                        expected,
                        path,
                        Divergence.Kind.ARG_OUT,
                        "arg"
                                + i
                                + " after call: "
                                + Values.explain(sig.params[i], expected.argsAfter[i], after));
            }
        }
    }

    /** Answers the backend's upcalls from the recording. */
    private Object onUpcall(Sig sig, Object[] args) {
        report.countUpcall(sig.name);
        Frame frame = frames.peek();
        if (frame == null) {
            report.add(
                    new Divergence(
                            rootIndex,
                            -1,
                            "(no native call running)",
                            Divergence.Kind.EXTRA_UPCALL,
                            sig.name,
                            sig.name + describeArgs(sig, args),
                            "upcall outside of any replayed native call"));
            return Values.defaultUpcallResult(sig, args);
        }
        List<UpcallNode> ups = frame.call.upcalls;
        while (frame.cursor < ups.size()
                && ups.get(frame.cursor).sig != sig
                && options.looseUpcalls().contains(ups.get(frame.cursor).sig)) {
            frame.cursor++;
        }
        UpcallNode exp = frame.cursor < ups.size() ? ups.get(frame.cursor) : null;
        String upPath = frame.path + " > upcall " + sig.name;
        if (exp == null || exp.sig != sig) {
            if (!options.looseUpcalls().contains(sig)) {
                diverge(
                        frame.call,
                        exp != null ? exp.seq : -1,
                        upPath,
                        Divergence.Kind.EXTRA_UPCALL,
                        sig.name,
                        "unexpected upcall "
                                + sig.name
                                + describeArgs(sig, args)
                                + (exp != null
                                        ? "; recording has " + exp.signatureWithArgs() + " next"
                                        : "; recording has no further upcalls for this call"));
            }
            return Values.defaultUpcallResult(sig, args);
        }
        frame.cursor++;
        for (int i = 0; i < args.length; i++) {
            Object actual = Values.snapshot(sig.params[i], args[i]);
            if (!Values.same(sig.params[i], exp.args[i], actual)) {
                diverge(
                        frame.call,
                        exp.seq,
                        upPath,
                        Divergence.Kind.UPCALL_ARGS,
                        sig.name,
                        "arg" + i + " " + Values.explain(sig.params[i], exp.args[i], actual));
            }
        }
        for (CallNode nested : exp.calls) {
            replayCall(nested, upPath + " > " + nested.sig.name);
        }
        if (exp.threw()) {
            throw new TraceWriter.RecordedException(exp.thrownClass, exp.thrownMessage);
        }
        if (exp.ret == null && (sig.ret == ValueKind.BOOLEAN || sig.ret == ValueKind.INT)) {
            return Values.defaultUpcallResult(sig, args); // unfinished recording
        }
        return exp.ret;
    }

    private void diverge(CallNode c, String path, Divergence.Kind kind, String detail) {
        diverge(c, c.seq, path, kind, c.sig.name, detail);
    }

    private void diverge(
            CallNode c, long seq, String path, Divergence.Kind kind, String method, String detail) {
        report.add(
                new Divergence(rootIndex, seq, path, kind, method, c.signatureWithArgs(), detail));
    }

    private static String describeArgs(Sig sig, Object[] args) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(Values.describe(sig.params[i], Values.snapshot(sig.params[i], args[i])));
        }
        return sb.append(')').toString();
    }

    private final class Responder implements UpcallHandler {
        @Override
        public boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
            return (Boolean)
                    onUpcall(
                            BulletApi.UPDATE_PHYSICS_FOR_LEVEL_IF_NEEDED,
                            new Object[] {wx, wy, level});
        }

        @Override
        public void onVehicleConstraintImpulse(int constraintId, int a, int b, float impulse) {
            onUpcall(
                    BulletApi.ON_VEHICLE_CONSTRAINT_IMPULSE,
                    new Object[] {constraintId, a, b, impulse});
        }

        @Override
        public void nativeLog(String logType, String logSeverity, String logText) {
            onUpcall(BulletApi.NATIVE_LOG, new Object[] {logType, logSeverity, logText});
        }

        @Override
        public String getBoneName(int ordinal) {
            return (String) onUpcall(BulletApi.GET_BONE_NAME, new Object[] {ordinal});
        }

        @Override
        public int getBoneOrdinal(String name) {
            return (Integer) onUpcall(BulletApi.GET_BONE_ORDINAL, new Object[] {name});
        }
    }
}
