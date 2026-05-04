package io.pzstorm.storm.commands;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import zombie.characters.Capability;
import zombie.characters.CharacterStat;
import zombie.characters.Role;
import zombie.characters.Stats;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

/**
 * Server-side command exercising the {@link io.pzstorm.storm.patch.performance.StatsGetPatch} on a
 * live JVM. Runs both a correctness check (return values across set, default, and zero-default
 * cases) and an allocation measurement (per-call thread-allocation bytes after JIT warmup).
 *
 * <p>The patch replaces {@code Stats.get(CharacterStat).getOrDefault(stat, stat.getDefaultValue())}
 * with a {@code Map.get} + null-check, eliminating the eager autobox of the default float. Without
 * the patch, every call allocates one {@link Float} (≥ 16 bytes); with the patch, the read path
 * allocates nothing beyond JIT-internal noise.
 *
 * <p>Output format: {@code RESULT ok=<bool> correctness=<pass|reason> calls=<n> allocBytes=<n>
 * allocPerCall=<f>} or {@code RESULT ERROR <kind>: <message>}.
 *
 * <p>Sink writes to {@link #SINK} are public + volatile to defeat dead-code elimination on the
 * tight measurement loop, so the JIT cannot prove the result is unused and skip the calls.
 */
@CommandName(name = "stormteststatsget")
@CommandHelp(
        helpText =
                "Exercises Stats.get(CharacterStat) for correctness and per-call allocation;"
                        + " emits a single RESULT line consumed by StatsGetPatchLiveTest.",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestStatsGetCommand extends CommandBase {

    public static volatile float SINK;

    private static final int WARMUP_CALLS = 200_000;
    private static final int MEASURED_CALLS = 1_000_000;

    public TestStatsGetCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() {
        return Command();
    }

    @Override
    protected String Command() {
        try {
            Stats stats = new Stats();
            stats.set(CharacterStat.PAIN, 42.5f);
            stats.set(CharacterStat.HUNGER, 0.7f);
            stats.set(CharacterStat.STRESS, 0.0f); // explicit zero — distinct from "unset"

            String correctness = checkCorrectness(stats);
            if (!"pass".equals(correctness)) {
                return "RESULT ok=false correctness=" + correctness;
            }

            ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!tmx.isThreadAllocatedMemorySupported()) {
                return "RESULT ERROR thread_alloc_unsupported";
            }
            if (!tmx.isThreadAllocatedMemoryEnabled()) {
                tmx.setThreadAllocatedMemoryEnabled(true);
            }
            long tid = Thread.currentThread().threadId();

            // Warm up — give the JIT enough samples to compile the call site.
            float local = 0.0f;
            for (int i = 0; i < WARMUP_CALLS; i++) {
                local += stats.get(CharacterStat.PAIN);
                local += stats.get(CharacterStat.ENDURANCE); // unset → default 1.0f path
                local += stats.get(CharacterStat.STRESS); // explicit-zero path
            }
            SINK = local;

            long beforeAlloc = tmx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < MEASURED_CALLS; i++) {
                local += stats.get(CharacterStat.PAIN);
            }
            long afterAlloc = tmx.getThreadAllocatedBytes(tid);
            SINK = local;

            long delta = afterAlloc - beforeAlloc;
            double perCall = delta / (double) MEASURED_CALLS;

            return "RESULT ok=true correctness=pass calls="
                    + MEASURED_CALLS
                    + " allocBytes="
                    + delta
                    + " allocPerCall="
                    + perCall;
        } catch (Exception e) {
            return "RESULT ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Returns {@code "pass"} on success or a short reason describing which case failed. Each case
     * exercises a different control-flow path through the patched {@code Stats.get}.
     */
    private static String checkCorrectness(Stats stats) {
        // 1. Stat that was explicitly set to a non-zero value
        float pain = stats.get(CharacterStat.PAIN);
        if (pain != 42.5f) return "set_value_mismatch_pain:" + pain;

        // 2. Stat never set → must return its registered default (non-zero)
        float endurance = stats.get(CharacterStat.ENDURANCE);
        if (endurance != CharacterStat.ENDURANCE.getDefaultValue()) {
            return "default_value_mismatch_endurance:"
                    + endurance
                    + "_expected_"
                    + CharacterStat.ENDURANCE.getDefaultValue();
        }

        // 3. Stat explicitly set to 0.0f — distinguishes "set" vs "default" for the null-check
        float stress = stats.get(CharacterStat.STRESS);
        if (stress != 0.0f) return "zero_value_mismatch_stress:" + stress;

        // 4. Stat with default 0.0f, never set
        float anger = stats.get(CharacterStat.ANGER);
        if (anger != 0.0f) return "default_zero_mismatch_anger:" + anger;

        // 5. Read after re-set updates the value
        stats.set(CharacterStat.PAIN, 17.5f);
        float painAfter = stats.get(CharacterStat.PAIN);
        if (painAfter != 17.5f) return "reset_value_mismatch_pain:" + painAfter;

        return "pass";
    }
}
