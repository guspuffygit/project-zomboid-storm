import io.pzstorm.storm.shader.StormModelShaderCache;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import sun.misc.Unsafe;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.util.Lambda;

/** Native warm manager lookup plus an immediate render-submission fixture; never calls OpenGL. */
public final class ModelShaderCacheBenchmark {
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final long MIN_ARM_CPU_NANOS = 250_000_000L;
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        if (!CPU.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("CPU timer unavailable");
        if (!CPU.isThreadCpuTimeEnabled()) CPU.setThreadCpuTimeEnabled(true);
        int queries = args.length == 0 ? 1_000_000 : Integer.parseInt(args[0]);
        if (queries <= 0) throw new IllegalArgumentException("queries per batch must be positive");
        Field shadersField = ShaderManager.class.getDeclaredField("shaders");
        shadersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ArrayList<Shader> nativeShaders = (ArrayList<Shader>) shadersField.get(ShaderManager.instance);
        ArrayList<Shader> previous = new ArrayList<>(nativeShaders);
        System.out.println("fixture=native ShaderManager warm lookup and immediate Lambda render submission; no GL, no real queue/wait");
        System.out.println("java=" + System.getProperty("java.runtime.version")
                + "; timer=current thread CPU; queries_per_batch=" + queries
                + "; minimum_arm_cpu_ms=250; repeated full batches; exact total queries denominator");
        System.out.println("scenario,shader_variants,round,native_ns_per_query,patched_ns_per_query,checksum");
        try {
            for (int variants : new int[] {1, 16, 64, 256}) {
                nativeShaders.clear();
                StormModelShaderCache.clear();
                String[] names = new String[variants];
                for (int i = 0; i < variants; i++) {
                    names[i] = "fixtureShader" + i;
                    Shader shader = shader(names[i]);
                    nativeShaders.add(shader);
                    StormModelShaderCache.record(ShaderManager.instance, names[i], false, false, shader);
                }
                for (boolean submission : new boolean[] {false, true}) {
                    for (int warmup = 0; warmup < 4; warmup++) {
                        run(names, submission, false, 500_000);
                        run(names, submission, true, 500_000);
                    }
                    double[] nativeTimes = new double[7];
                    double[] patchedTimes = new double[7];
                    for (int round = 0; round < 7; round++) {
                        if ((round & 1) == 0) {
                            nativeTimes[round] = measure(names, submission, false, queries);
                            patchedTimes[round] = measure(names, submission, true, queries);
                        } else {
                            patchedTimes[round] = measure(names, submission, true, queries);
                            nativeTimes[round] = measure(names, submission, false, queries);
                        }
                        System.out.printf(Locale.ROOT, "%s,%d,%d,%.4f,%.4f,%d%n",
                                submission ? "immediate-submission" : "manager-lookup", variants,
                                round, nativeTimes[round], patchedTimes[round], sink);
                    }
                    Arrays.sort(nativeTimes);
                    Arrays.sort(patchedTimes);
                    System.out.printf(Locale.ROOT, "%s,%d,median,%.4f,%.4f,change=%.2f%%%n",
                            submission ? "immediate-submission" : "manager-lookup", variants,
                            nativeTimes[3], patchedTimes[3],
                            (patchedTimes[3] / nativeTimes[3] - 1.0) * 100.0);
                }
            }
        } finally {
            StormModelShaderCache.clear();
            nativeShaders.clear();
            nativeShaders.addAll(previous);
        }
    }

    private static double measure(String[] names, boolean submission, boolean patched, int queries) {
        long start = CPU.getCurrentThreadCpuTime();
        long totalQueries = 0;
        long elapsedCpu;
        // Windows may expose CPU time in ~15.625 ms ticks. One fast batch can otherwise
        // measure zero or a single tick; accumulate complete, independently checked batches.
        do {
            run(names, submission, patched, queries);
            totalQueries += queries;
            elapsedCpu = CPU.getCurrentThreadCpuTime() - start;
        } while (elapsedCpu < MIN_ARM_CPU_NANOS);
        return elapsedCpu / (double) totalQueries;
    }

    private static void run(String[] names, boolean submission, boolean patched, int queries) {
        ModelSlot model = new ModelSlot();
        FixtureQueue.calls = 0;
        long checksum = 0;
        for (int i = 0; i < queries; i++) {
            String name = names[(i * 73) & (names.length - 1)];
            if (submission) {
                if (patched) patchedCreate(model, name);
                else nativeCreate(model, name);
            } else {
                model.effect = patched ? StormModelShaderCache.find(name, false, false)
                        : ShaderManager.instance.getOrCreateShader(name, false, false);
            }
            if (model.effect == null) throw new AssertionError("unexpected fixture miss");
            checksum += model.effect.getName().hashCode();
        }
        long expectedSubmissions = submission && !patched ? queries : 0;
        if (FixtureQueue.calls != expectedSubmissions) throw new AssertionError("queue submission count mismatch");
        sink = checksum;
    }

    // The native 42.21.0 CreateShader expression, with its render callback executed immediately.
    private static void nativeCreate(ModelSlot model, String name) {
        if (FixtureQueue.noOpenGL) return;
        Lambda.invoke(FixtureQueue::invoke, model, name,
                (lThis, lName) -> lThis.effect = ShaderManager.instance.getOrCreateShader(lName, lThis.isStatic, false));
    }

    private static void patchedCreate(ModelSlot model, String name) {
        if (!FixtureQueue.noOpenGL) {
            Shader cached = StormModelShaderCache.find(name, model.isStatic, false);
            if (cached != null) {
                model.effect = cached;
                return;
            }
        }
        nativeCreate(model, name);
    }

    private static Shader shader(String name) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Shader shader = (Shader) ((Unsafe) unsafeField.get(null)).allocateInstance(Shader.class);
        Field nameField = Shader.class.getDeclaredField("name");
        nameField.setAccessible(true);
        nameField.set(shader, name);
        // Both actual native boolean fields are false; shaderProgram remains null.
        return shader;
    }

    private static final class ModelSlot {
        Shader effect;
        boolean isStatic;
    }

    private static final class FixtureQueue {
        static long calls;
        static boolean noOpenGL;

        static void invoke(Runnable callback) {
            calls++;
            callback.run();
        }
    }
}
