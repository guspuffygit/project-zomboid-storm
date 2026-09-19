package io.pzstorm.storm.bullet.trace.scenario;

import io.pzstorm.storm.bullet.trace.BulletBackend;
import io.pzstorm.storm.bullet.trace.SkeletonBoneTable;
import io.pzstorm.storm.bullet.trace.TraceRecorder;
import io.pzstorm.storm.bullet.trace.UpcallHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * What a running scenario gets: the (recorded) library, a seeded random source, the game's command
 * buffer, a terrain model, and game-like answers to the library's upcalls.
 */
public final class ScenarioContext {

    /** A constraint impulse reported by the library ({@code Bullet.onVehicleConstraintImpulse}). */
    public record Impulse(int constraintId, int vehicleIdA, int vehicleIdB, float impulse) {}

    public final BulletBackend bullet;
    public final Random random;
    public final long seed;
    public final CmdBuf cmd;
    public final Terrain terrain;

    /** {@code Bullet.init} has run in this JVM (the game boots the library exactly once). */
    public boolean booted;

    /** {@code RagdollBuilder.instance.initialized}: a JVM-wide singleton in the game. */
    public boolean ragdollBuilderInitialized;

    /** {@code RagdollBuilder.mass} (default 70); {@code setRagdollMass} only fires on change. */
    public float ragdollMass = 70.0F;

    /**
     * When set, impulses are handed to it inside the upcall (as the game's {@code
     * Bullet.onVehicleConstraintImpulse} runs, so a {@code removeConstraint} it makes is nested in
     * {@code stepSimulation}) instead of being queued for {@link #drainImpulses()}.
     */
    public Consumer<Impulse> onImpulse;

    /** Impulses reported since the last {@link #drainImpulses()}. */
    private final List<Impulse> impulses = new ArrayList<>();

    private final List<String> logLines = new ArrayList<>();
    private final TraceRecorder recorder;

    public ScenarioContext(BulletBackend bullet, long seed, TraceRecorder recorder) {
        this.bullet = bullet;
        this.seed = seed;
        this.random = new Random(seed);
        this.cmd = new CmdBuf(bullet);
        this.terrain = new Terrain(seed * 31 + 7);
        this.recorder = recorder;
    }

    /** Adds a NOTE event to the trace (scenario phase markers); no-op when not recording. */
    public void note(String text) {
        if (recorder != null) {
            recorder.note(text);
        }
    }

    public List<Impulse> drainImpulses() {
        List<Impulse> l = List.copyOf(impulses);
        impulses.clear();
        return l;
    }

    /** Lines the library logged through {@code DebugLog.nativeLog}. */
    public List<String> logLines() {
        return logLines;
    }

    /** float in [lo, hi). */
    public float range(float lo, float hi) {
        return lo + random.nextFloat() * (hi - lo);
    }

    /** The upcall answers a real game would give, backed by {@link #terrain}. */
    UpcallHandler gameUpcalls() {
        return new UpcallHandler() {
            @Override
            public boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
                return terrain.updatePhysicsForLevelIfNeeded(cmd, wx, wy, level);
            }

            @Override
            public void onVehicleConstraintImpulse(
                    int constraintId, int vehicleIdA, int vehicleIdB, float impulse) {
                Impulse im = new Impulse(constraintId, vehicleIdA, vehicleIdB, impulse);
                if (onImpulse != null) {
                    onImpulse.accept(im);
                } else {
                    impulses.add(im);
                }
            }

            @Override
            public void nativeLog(String logType, String logSeverity, String logText) {
                logLines.add(logType + " " + logSeverity + " " + logText);
            }

            @Override
            public String getBoneName(int ordinal) {
                return SkeletonBoneTable.getBoneName(ordinal);
            }

            @Override
            public int getBoneOrdinal(String name) {
                return SkeletonBoneTable.getBoneOrdinal(name);
            }
        };
    }
}
