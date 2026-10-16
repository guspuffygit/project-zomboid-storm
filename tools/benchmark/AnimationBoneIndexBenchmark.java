import io.pzstorm.storm.animation.StormBoneIndexLookup;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

/** Isolated CPU fixture, not a native gameplay or FPS benchmark. */
public final class AnimationBoneIndexBenchmark {
    private static final String[] BONES = {"Bip01_Head", "Bip01_L_Foot", "Bip01_R_Foot"};
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static volatile long sink;

    public static void main(String[] args) {
        if (!CPU.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("CPU timer unavailable");
        if (!CPU.isThreadCpuTimeEnabled()) CPU.setThreadCpuTimeEnabled(true);
        int queries = args.length == 0 ? 8_000_000 : Integer.parseInt(args[0]);
        HashMap<String, Integer>[] maps = maps();
        System.out.println("fixture=AnimationPlayer bone lookup; skeleton maps=" + maps.length
                + "; timer=current thread CPU; queries_per_batch=" + queries
                + "; sample_min_cpu_ms=250; ns_per_query uses exact queries across completed batches");
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        System.out.println("scenario,round,native_ns_per_query,patched_ns_per_query,checksum");
        for (int scenario = 0; scenario < 3; scenario++) {
            for (int warmup = 0; warmup < 6; warmup++) {
                run(maps, scenario, false, 2_000_000);
                run(maps, scenario, true, 2_000_000);
            }
            double[] nativeTimes = new double[9];
            double[] patchedTimes = new double[9];
            for (int round = 0; round < 9; round++) {
                if ((round & 1) == 0) {
                    nativeTimes[round] = measure(maps, scenario, false, queries);
                    patchedTimes[round] = measure(maps, scenario, true, queries);
                } else {
                    patchedTimes[round] = measure(maps, scenario, true, queries);
                    nativeTimes[round] = measure(maps, scenario, false, queries);
                }
                System.out.printf(Locale.ROOT, "%s,%d,%.4f,%.4f,%d%n",
                        scenarioName(scenario), round, nativeTimes[round], patchedTimes[round], sink);
            }
            Arrays.sort(nativeTimes);
            Arrays.sort(patchedTimes);
            System.out.printf(Locale.ROOT, "%s,median,%.4f,%.4f,change=%.2f%%%n",
                    scenarioName(scenario), nativeTimes[4], patchedTimes[4],
                    (patchedTimes[4] / nativeTimes[4] - 1.0) * 100.0);
        }
    }

    private static double measure(HashMap<String, Integer>[] maps, int scenario, boolean patched, int queries) {
        if (queries <= 0) throw new IllegalArgumentException("queries per batch must be positive");
        long start = CPU.getCurrentThreadCpuTime();
        long totalQueries = 0;
        long elapsed;
        do {
            run(maps, scenario, patched, queries);
            totalQueries = Math.addExact(totalQueries, queries);
            elapsed = CPU.getCurrentThreadCpuTime() - start;
        } while (elapsed < 250_000_000L);
        return elapsed / (double) totalQueries;
    }

    private static void run(HashMap<String, Integer>[] maps, int scenario, boolean patched, int queries) {
        long sum = 0;
        for (int i = 0; i < queries; i++) {
            HashMap<String, Integer> map = maps[i & (maps.length - 1)];
            String bone = scenario == 2 || scenario == 1 && (i & 15) == 0 ? "missing" : BONES[i % 3];
            if (patched) {
                Integer index = StormBoneIndexLookup.existingIndex(map, bone);
                sum += index == null ? nativeLookup(map, bone, -1) : index;
            } else {
                sum += nativeLookup(map, bone, -1);
            }
        }
        sink = sum;
    }

    // Exact native 42.21.0 lookup body after the pure getSkinningBoneIndices getter.
    private static int nativeLookup(HashMap<String, Integer> indices, String boneName, int defaultValue) {
        return indices != null && indices.containsKey(boneName) ? indices.get(boneName) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, Integer>[] maps() {
        // SkinningData is shared by characters using the same body model; keep a small hot set.
        HashMap<String, Integer>[] maps = new HashMap[16];
        for (int i = 0; i < maps.length; i++) {
            HashMap<String, Integer> indices = new HashMap<>();
            for (int bone = 0; bone < 60; bone++) indices.put("fixtureBone" + bone, bone);
            for (int bone = 0; bone < BONES.length; bone++) indices.put(BONES[bone], (i + bone) % 60);
            maps[i] = indices;
        }
        return maps;
    }

    private static String scenarioName(int scenario) {
        return switch (scenario) {
            case 0 -> "existing-shadow-bones";
            case 1 -> "one-in-sixteen-missing";
            default -> "all-missing-fallback";
        };
    }
}
