package io.pzstorm.storm.bullet.trace;

import io.pzstorm.storm.bullet.trace.scenario.Scenario;
import io.pzstorm.storm.bullet.trace.scenario.ScenarioRunner;
import io.pzstorm.storm.bullet.trace.scenario.Scenarios;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Command line of the differential harness. Run by the native harness ({@code NativeHarnessMain},
 * backend = the real library) and directly for the Java port ({@code --backend java}).
 *
 * <pre>
 * list-scenarios
 * scenario --name N [--seed S] --out trace.pzbt[.gz]      run a scenario live, record it
 * replay   --trace T [--out result.pzbt.gz] [--report R] [--max-div N] [--stop-at-first]
 *          [--loose nativeLog,getBoneName,...]            replay + bit-exact compare
 * diff     A B [--report R] [--max-div N]                 compare two traces
 * info     T                                              header + counts
 * dump     T [--limit N]                                  human-readable call trees
 * </pre>
 *
 * Exit status: 0 identical / ok, 1 diverged, 2 usage or runtime error.
 */
public final class TraceTool {

    private TraceTool() {}

    public static void main(String[] args) {
        Map<String, String> opts = parse(args, new ArrayList<>());
        String backend = opts.getOrDefault("backend", "java");
        if (!backend.equals("java")) {
            System.err.println(
                    "only --backend java is available here; the native backend runs in the harness");
            System.exit(2);
        }
        System.exit(
                run(args, () -> JavaBackend.open(TraceTool.class.getClassLoader()), System.out));
    }

    /**
     * @return exit status
     */
    public static int run(String[] args, Callable<BackendSession> backend, PrintStream out) {
        List<String> pos = new ArrayList<>();
        Map<String, String> o = parse(args, pos);
        if (pos.isEmpty()) {
            out.println(
                    "usage: list-scenarios | scenario | replay | diff | info | dump (see TraceTool javadoc)");
            return 2;
        }
        try {
            switch (pos.get(0)) {
                case "list-scenarios" -> {
                    for (Scenario s : Scenarios.all()) {
                        out.printf("%-24s %s%n", s.name(), s.description());
                    }
                    return 0;
                }
                case "scenario" -> {
                    Scenario s = Scenarios.get(req(o, "name"));
                    long seed = Long.parseLong(o.getOrDefault("seed", "1"));
                    Path path = Path.of(req(o, "out"));
                    BackendSession session = backend.call();
                    long t0 = System.nanoTime();
                    long events = ScenarioRunner.run(s, seed, session, path, out::println);
                    out.printf(
                            "scenario %s seed %d on %s: %d events -> %s (%d bytes, %d ms)%n",
                            s.name(),
                            seed,
                            session.name(),
                            events,
                            path,
                            Files.size(path),
                            (System.nanoTime() - t0) / 1_000_000);
                    return 0;
                }
                case "replay" -> {
                    return replay(o, backend, out);
                }
                case "diff" -> {
                    if (pos.size() < 3) {
                        out.println("diff needs two trace paths");
                        return 2;
                    }
                    ReplayReport r =
                            TraceDiff.diff(
                                    Path.of(pos.get(1)),
                                    Path.of(pos.get(2)),
                                    Integer.parseInt(o.getOrDefault("max-div", "50")));
                    return finish(r, o, out);
                }
                case "info" -> {
                    info(Path.of(pos.get(1)), out);
                    return 0;
                }
                case "dump" -> {
                    dump(Path.of(pos.get(1)), Long.parseLong(o.getOrDefault("limit", "200")), out);
                    return 0;
                }
                default -> {
                    out.println("unknown command " + pos.get(0));
                    return 2;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace(out);
            return 2;
        }
    }

    private static int replay(
            Map<String, String> o, Callable<BackendSession> backend, PrintStream out)
            throws Exception {
        Path trace = Path.of(req(o, "trace"));
        Replayer.Options opt =
                Replayer.Options.defaults()
                        .withMaxKept(Integer.parseInt(o.getOrDefault("max-div", "50")))
                        .withStopAtFirst(o.containsKey("stop-at-first"))
                        .withProgress(
                                out::println, Long.parseLong(o.getOrDefault("progress", "0")));
        if (o.containsKey("loose")) {
            Set<Sig> loose = new HashSet<>();
            for (String name : o.get("loose").split(",")) {
                for (Sig s : BulletApi.UPCALLS) {
                    if (s.name.equals(name.trim())) {
                        loose.add(s);
                    }
                }
            }
            opt = opt.withLoose(loose);
        }
        BackendSession session = backend.call();
        TraceRecorder recorder = null;
        try (TraceReader reader = TraceReader.open(trace)) {
            if (o.containsKey("out")) {
                Map<String, String> meta = new LinkedHashMap<>(reader.header().metadata());
                meta.put("source", "replay");
                meta.put("replayOf", trace.toString());
                meta.put("backend", session.name());
                meta.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
                meta.put(
                        "java",
                        System.getProperty("java.vendor")
                                + " "
                                + System.getProperty("java.version"));
                recorder =
                        new TraceRecorder(
                                TraceWriter.open(Path.of(o.get("out")), meta), out::println);
                session = RecordingBackend.wrap(session, recorder);
            }
            ReplayReport report =
                    new ReplayReport("replay of " + trace + " on " + session.name(), opt.maxKept());
            report.info("recording meta: " + reader.header().metadata());
            long t0 = System.nanoTime();
            new Replayer(session, report, opt).replay(reader);
            report.info("replay time: " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            if (recorder != null) {
                recorder.close();
                recorder = null;
                report.info("result trace: " + o.get("out"));
            }
            return finish(report, o, out);
        } finally {
            if (recorder != null) {
                recorder.close();
            }
        }
    }

    private static int finish(ReplayReport r, Map<String, String> o, PrintStream out)
            throws Exception {
        String text = r.render();
        out.print(text);
        if (o.containsKey("report")) {
            r.write(Path.of(o.get("report")));
        }
        return r.identical() ? 0 : 1;
    }

    private static void info(Path p, PrintStream out) throws Exception {
        Map<String, long[]> counts = new java.util.TreeMap<>();
        long[] roots = new long[1];
        try (TraceReader r = TraceReader.open(p)) {
            out.println("version " + r.header().version() + " meta " + r.header().metadata());
            r.forEachRoot(
                    c -> {
                        roots[0]++;
                        countTree(c, counts);
                    });
            TraceStats s = r.stats();
            out.println(
                    "events "
                            + s.events()
                            + ", top-level calls "
                            + roots[0]
                            + ", clean end "
                            + s.cleanEnd()
                            + ", truncated "
                            + s.truncated()
                            + ", unfinished "
                            + s.unfinishedRoots()
                            + ", overlapping "
                            + s.overlappingRoots()
                            + ", threads "
                            + s.threads());
            if (s.problem() != null) {
                out.println("problem: " + s.problem());
            }
            for (String n : s.notes()) {
                out.println("note: " + n);
            }
        }
        counts.forEach((k, v) -> out.printf("  %-40s %d%n", k, v[0]));
    }

    private static void countTree(CallNode c, Map<String, long[]> counts) {
        counts.computeIfAbsent(c.sig.name, k -> new long[1])[0]++;
        for (UpcallNode u : c.upcalls) {
            counts.computeIfAbsent("^" + u.sig.name, k -> new long[1])[0]++;
            for (CallNode n : u.calls) {
                countTree(n, counts);
            }
        }
    }

    private static void dump(Path p, long limit, PrintStream out) throws Exception {
        try (TraceReader r = TraceReader.open(p)) {
            out.println("meta " + r.header().metadata());
            CallNode c;
            long i = 0;
            while ((c = r.nextRoot()) != null && i < limit) {
                dumpCall(c, "", out);
                i++;
            }
        }
    }

    private static void dumpCall(CallNode c, String indent, PrintStream out) {
        StringBuilder sb = new StringBuilder(indent).append(c);
        if (c.threw()) {
            sb.append(" THREW ").append(c.thrownClass).append(": ").append(c.thrownMessage);
        } else if (c.sig.ret != ValueKind.VOID) {
            sb.append(" = ").append(Values.describe(c.sig.ret, c.ret));
        }
        for (int i = 0; i < c.sig.params.length; i++) {
            if (c.sig.params[i].isMutable() && c.sig.params[i] != ValueKind.BYTE_BUFFER) {
                sb.append(" arg")
                        .append(i)
                        .append("'=")
                        .append(Values.describe(c.sig.params[i], c.argsAfter[i]));
            }
        }
        out.println(sb);
        for (UpcallNode u : c.upcalls) {
            out.println(
                    indent
                            + "  ^ "
                            + u.signatureWithArgs()
                            + (u.sig.ret != ValueKind.VOID
                                    ? " = " + Values.describe(u.sig.ret, u.ret)
                                    : ""));
            for (CallNode n : u.calls) {
                dumpCall(n, indent + "    ", out);
            }
        }
    }

    private static String req(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null) {
            throw new IllegalArgumentException("missing --" + k);
        }
        return v;
    }

    /** --key value / --flag, rest positional. */
    static Map<String, String> parse(String[] args, List<String> positional) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String k = a.substring(2);
                int eq = k.indexOf('=');
                if (eq >= 0) {
                    m.put(k.substring(0, eq), k.substring(eq + 1));
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    m.put(k, args[++i]);
                } else {
                    m.put(k, "true");
                }
            } else {
                positional.add(a);
            }
        }
        return m;
    }
}
