package io.pzstorm.storm.bullet.trace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Compares two traces call tree by call tree, bit-exactly: inputs, outputs, upcalls (arguments and
 * results) and nested calls. Thread ids, thread names, sequence numbers and notes are ignored, so a
 * recording made in the game and a replay's result trace are comparable. Used for the native
 * self-consistency check (two replays in fresh JVMs) and for Docker-vs-machine comparisons.
 */
public final class TraceDiff {

    private final ReplayReport report;
    private long rootIndex;

    private TraceDiff(ReplayReport report) {
        this.report = report;
    }

    public static ReplayReport diff(Path a, Path b, int maxKept) throws IOException {
        ReplayReport report = new ReplayReport("trace diff " + a + " vs " + b, maxKept);
        try (TraceReader ra = TraceReader.open(a);
                TraceReader rb = TraceReader.open(b)) {
            TraceDiff d = new TraceDiff(report);
            while (true) {
                CallNode x = ra.nextRoot();
                CallNode y = rb.nextRoot();
                if (x == null || y == null) {
                    if (x != null || y != null) {
                        long extraA = x != null ? 1 + count(ra) : 0;
                        long extraB = y != null ? 1 + count(rb) : 0;
                        CallNode first = x != null ? x : y;
                        report.add(
                                new Divergence(
                                        d.rootIndex,
                                        first.seq,
                                        first.sig.name,
                                        Divergence.Kind.STRUCTURE,
                                        first.sig.name,
                                        first.signatureWithArgs(),
                                        "top-level call counts differ: first has "
                                                + extraA
                                                + " more, second has "
                                                + extraB
                                                + " more"));
                    }
                    break;
                }
                report.countRoot();
                d.compareCall(x, y, x.sig.name);
                d.rootIndex++;
            }
            report.info(
                    "first:  "
                            + a
                            + " "
                            + ra.stats().events()
                            + " events, meta "
                            + ra.header().metadata());
            report.info(
                    "second: "
                            + b
                            + " "
                            + rb.stats().events()
                            + " events, meta "
                            + rb.header().metadata());
        }
        return report;
    }

    /** Compares in-memory call lists (tests). */
    public static ReplayReport diff(List<CallNode> a, List<CallNode> b, int maxKept) {
        ReplayReport report = new ReplayReport("trace diff", maxKept);
        TraceDiff d = new TraceDiff(report);
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            report.countRoot();
            d.compareCall(a.get(i), b.get(i), a.get(i).sig.name);
            d.rootIndex++;
        }
        if (a.size() != b.size()) {
            CallNode first = a.size() > n ? a.get(n) : b.get(n);
            report.add(
                    new Divergence(
                            n,
                            first.seq,
                            first.sig.name,
                            Divergence.Kind.STRUCTURE,
                            first.sig.name,
                            first.signatureWithArgs(),
                            "top-level call counts differ: " + a.size() + " vs " + b.size()));
        }
        return report;
    }

    private static long count(TraceReader r) throws IOException {
        long n = 0;
        while (r.nextRoot() != null) {
            n++;
        }
        return n;
    }

    private void compareCall(CallNode a, CallNode b, String path) {
        report.countCall(a.sig.name);
        if (!a.sig.equals(b.sig)) {
            add(a, path, Divergence.Kind.CALL_MISMATCH, "first calls " + a + ", second calls " + b);
            return;
        }
        Sig sig = a.sig;
        for (int i = 0; i < sig.params.length; i++) {
            if (!Values.same(sig.params[i], a.args[i], b.args[i])) {
                add(
                        a,
                        path,
                        Divergence.Kind.CALL_MISMATCH,
                        "input arg"
                                + i
                                + " "
                                + Values.explain(sig.params[i], a.args[i], b.args[i]));
            }
        }
        if (!Objects.equals(a.thrownClass, b.thrownClass)
                || !Objects.equals(a.thrownMessage, b.thrownMessage)) {
            add(
                    a,
                    path,
                    Divergence.Kind.EXCEPTION,
                    "first "
                            + a.thrownClass
                            + ": "
                            + a.thrownMessage
                            + ", second "
                            + b.thrownClass
                            + ": "
                            + b.thrownMessage);
        } else if (!a.threw() && sig.ret != ValueKind.VOID && !Values.same(sig.ret, a.ret, b.ret)) {
            add(a, path, Divergence.Kind.RETURN, "return " + Values.explain(sig.ret, a.ret, b.ret));
        }
        for (int i = 0; i < sig.params.length; i++) {
            if (sig.params[i].isMutable()
                    && !Values.same(sig.params[i], a.argsAfter[i], b.argsAfter[i])) {
                add(
                        a,
                        path,
                        Divergence.Kind.ARG_OUT,
                        "arg"
                                + i
                                + " after call: "
                                + Values.explain(sig.params[i], a.argsAfter[i], b.argsAfter[i]));
            }
        }
        int n = Math.min(a.upcalls.size(), b.upcalls.size());
        for (int i = 0; i < n; i++) {
            UpcallNode ua = a.upcalls.get(i);
            UpcallNode ub = b.upcalls.get(i);
            String upPath = path + " > upcall " + ua.sig.name;
            report.countUpcall(ua.sig.name);
            if (!ua.sig.equals(ub.sig)) {
                add(
                        a,
                        ua.seq,
                        upPath,
                        Divergence.Kind.STRUCTURE,
                        ua.sig.name,
                        "upcall #"
                                + i
                                + ": first "
                                + ua.signatureWithArgs()
                                + ", second "
                                + ub.signatureWithArgs());
                return;
            }
            for (int j = 0; j < ua.args.length; j++) {
                if (!Values.same(ua.sig.params[j], ua.args[j], ub.args[j])) {
                    add(
                            a,
                            ua.seq,
                            upPath,
                            Divergence.Kind.UPCALL_ARGS,
                            ua.sig.name,
                            "upcall #"
                                    + i
                                    + " arg"
                                    + j
                                    + " "
                                    + Values.explain(ua.sig.params[j], ua.args[j], ub.args[j]));
                }
            }
            if (!Objects.equals(ua.thrownClass, ub.thrownClass)
                    || (ua.sig.ret != ValueKind.VOID && !Values.same(ua.sig.ret, ua.ret, ub.ret))) {
                add(
                        a,
                        ua.seq,
                        upPath,
                        Divergence.Kind.UPCALL_RESULT,
                        ua.sig.name,
                        "upcall #"
                                + i
                                + " result: "
                                + Values.explain(ua.sig.ret, ua.ret, ub.ret)
                                + " thrown "
                                + ua.thrownClass
                                + " / "
                                + ub.thrownClass);
            }
            int m = Math.min(ua.calls.size(), ub.calls.size());
            for (int j = 0; j < m; j++) {
                compareCall(
                        ua.calls.get(j),
                        ub.calls.get(j),
                        upPath + " > " + ua.calls.get(j).sig.name);
            }
            if (ua.calls.size() != ub.calls.size()) {
                add(
                        a,
                        ua.seq,
                        upPath,
                        Divergence.Kind.STRUCTURE,
                        ua.sig.name,
                        "upcall #"
                                + i
                                + " nested call counts differ: "
                                + ua.calls.size()
                                + " vs "
                                + ub.calls.size());
            }
        }
        if (a.upcalls.size() != b.upcalls.size()) {
            List<UpcallNode> longer = a.upcalls.size() > n ? a.upcalls : b.upcalls;
            add(
                    a,
                    longer.get(n).seq,
                    path,
                    Divergence.Kind.STRUCTURE,
                    sig.name,
                    "upcall counts differ: "
                            + a.upcalls.size()
                            + " vs "
                            + b.upcalls.size()
                            + "; first unmatched: "
                            + longer.get(n).signatureWithArgs());
        }
    }

    private void add(CallNode c, String path, Divergence.Kind kind, String detail) {
        add(c, c.seq, path, kind, c.sig.name, detail);
    }

    private void add(
            CallNode c, long seq, String path, Divergence.Kind kind, String method, String detail) {
        report.add(
                new Divergence(rootIndex, seq, path, kind, method, c.signatureWithArgs(), detail));
    }
}
