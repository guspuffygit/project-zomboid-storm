package io.pzstorm.storm.bullet.trace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Collects divergences and call counts; renders the text report. */
public final class ReplayReport {

    private final String title;
    private final int maxKept;
    private final List<Divergence> kept = new ArrayList<>();
    private final Map<String, long[]> perMethod = new TreeMap<>();
    private final Map<Divergence.Kind, Long> perKind = new EnumMap<>(Divergence.Kind.class);
    private final List<String> info = new ArrayList<>();
    private long divergences;
    private long rootCalls;
    private long calls;
    private long upcalls;
    private long divergentRoots;
    private long lastDivergentRoot = -1;

    /**
     * @param maxKept how many divergences to keep in full; the rest are only counted
     */
    public ReplayReport(String title, int maxKept) {
        this.title = title;
        this.maxKept = maxKept;
    }

    public void add(Divergence d) {
        divergences++;
        perKind.merge(d.kind(), 1L, Long::sum);
        perMethod.computeIfAbsent(d.method(), k -> new long[2])[1]++;
        if (d.rootIndex() != lastDivergentRoot) {
            divergentRoots++;
            lastDivergentRoot = d.rootIndex();
        }
        if (kept.size() < maxKept) {
            kept.add(d);
        }
    }

    public void countRoot() {
        rootCalls++;
    }

    public void countCall(String method) {
        calls++;
        perMethod.computeIfAbsent(method, k -> new long[2])[0]++;
    }

    public void countUpcall(String method) {
        upcalls++;
        perMethod.computeIfAbsent("^" + method, k -> new long[2])[0]++;
    }

    public void info(String line) {
        info.add(line);
    }

    public boolean identical() {
        return divergences == 0;
    }

    public long divergenceCount() {
        return divergences;
    }

    public List<Divergence> divergences() {
        return List.copyOf(kept);
    }

    public Divergence first() {
        return kept.isEmpty() ? null : kept.get(0);
    }

    public long rootCalls() {
        return rootCalls;
    }

    public long calls() {
        return calls;
    }

    public long upcalls() {
        return upcalls;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(title).append(" ===\n");
        for (String s : info) {
            sb.append(s).append('\n');
        }
        sb.append("top-level calls: ")
                .append(rootCalls)
                .append(", native calls (incl. nested): ")
                .append(calls)
                .append(", upcalls: ")
                .append(upcalls)
                .append('\n');
        sb.append("RESULT: ")
                .append(identical() ? "IDENTICAL (bit-exact)" : "DIVERGED")
                .append(", divergences: ")
                .append(divergences)
                .append(" in ")
                .append(divergentRoots)
                .append(" top-level call(s)\n");
        if (!identical()) {
            sb.append("\nFIRST DIVERGENCE\n  ").append(kept.get(0).format()).append("\n");
            sb.append("\nby kind:\n");
            perKind.forEach(
                    (k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
            sb.append("\nby method (calls / divergences):\n");
            perMethod.forEach(
                    (m, v) -> {
                        if (v[1] > 0) {
                            sb.append(String.format("  %-40s %10d / %d%n", m, v[0], v[1]));
                        }
                    });
            if (kept.size() > 1) {
                sb.append("\nfurther divergences (")
                        .append(kept.size() - 1)
                        .append(" of ")
                        .append(divergences - 1)
                        .append(" shown):\n");
                for (int i = 1; i < kept.size(); i++) {
                    sb.append("  ").append(kept.get(i).format()).append('\n');
                }
            }
        }
        sb.append("\ncall counts by method:\n");
        perMethod.forEach((m, v) -> sb.append(String.format("  %-40s %10d%n", m, v[0])));
        return sb.toString();
    }

    public void write(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, render(), StandardCharsets.UTF_8);
    }
}
