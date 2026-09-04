package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.metrics.ZombieRainWanderMetrics;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.network.GameServer;

/**
 * Verifies {@link ZombieRainWanderPatch} on two levels: that the advice lands on {@code
 * pickRandomWanderInterval} and nowhere else, and that the patched state, asked the real question
 * through the real (private) method with the engine's <em>real</em> weather running, lengthens a
 * raining interval to exactly the dry distribution and leaves everything else alone.
 *
 * <p>⭐ The weather is not stubbed. {@link RealWeather} sets the real {@code ClimateManager}
 * singleton's own {@code precipitationIntensity} and {@code precipitationIsSnow}, and the code
 * under test reaches it through the real {@code RainManager.isRaining()}. A pinned {@code
 * isRaining} would be testing the seam rather than the engine, which is the mistake the 1.0.3
 * animal stride shipped.
 *
 * <p>⭐ Every behaviour case is paired with the same call through the <em>unpatched</em> class.
 * {@link #vanillaControlShortensTheIntervalInRain()} is the anchor: it asserts that the engine
 * really does shorten idle wandering in rain. If that control ever stops failing to lengthen, the
 * defect this patch exists for is gone and the rest of the suite means nothing.
 */
class ZombieRainWanderPatchTest implements UnitTest {

    private static final String TARGET = "zombie.ai.states.ZombieIdleState";
    private static final String TARGET_RES = "zombie/ai/states/ZombieIdleState.class";
    private static final String DECIDER = "pickRandomWanderInterval";
    private static final String HELPER =
            "io/pzstorm/storm/patch/performance/ZombieRainWanderInterval";

    /** Vanilla {@code Rand.Next(400, 1000)}: inclusive of 400, exclusive of 1000. */
    private static final float RAW_MIN = 400f;

    private static final float RAW_MAX = 999f;

    /** Vanilla's dry branch, and equally a raining interval at 150%. */
    private static final float DRY_MIN = RAW_MIN * 1.5f;

    private static final float DRY_MAX = RAW_MAX * 1.5f;

    private static final int SAMPLES = 20_000;

    private boolean savedServerFlag;

    /**
     * ⛔ {@code GameServer.server} is process-wide; see {@code
     * RequestDataManagerJoinStallPatchTest}.
     */
    @BeforeEach
    void captureState() throws Exception {
        savedServerFlag = GameServer.server;
        ZombieRainWanderInterval.resetForTest();
        ZombieRainWanderMetrics.resetForTest();
        RealWeather.init();
    }

    @AfterEach
    void restoreState() throws Exception {
        GameServer.server = savedServerFlag;
        ZombieRainWanderInterval.resetForTest();
        ZombieRainWanderMetrics.resetForTest();
        RealWeather.dry();
    }

    // ---------------------------------------------------------------- placement

    @Test
    void adviceLandsOnTheWanderPickOnly() throws Exception {
        byte[] raw = readClassBytes();
        byte[] patched = new ZombieRainWanderPatch().transform(raw);
        assertNotNull(patched);
        assertTrue(patched.length > 0);
        assertEquals(0, helperCalls(raw, null), "vanilla must not call the helper anywhere");
        assertTrue(helperCalls(patched, DECIDER) >= 1, "advice must land in the wander pick");
        assertEquals(0, helperCalls(patched, "enter"), "must not touch enter");
        assertEquals(0, helperCalls(patched, "execute"), "must not touch execute");
        assertEquals(0, helperCalls(patched, "exit"), "must not touch exit");
        assertEquals(0, helperCalls(patched, "animEvent"), "must not touch animEvent");
    }

    /**
     * The target is private, so a rename in a future build would leave the advice quietly
     * unattached. The patch must refuse instead.
     */
    @Test
    void patchRefusesIfTheEngineMethodIsGone() throws Exception {
        byte[] renamed = renameDecider(readClassBytes());
        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> new ZombieRainWanderPatch().transform(renamed));
        Throwable cause = thrown.getCause() == null ? thrown : thrown.getCause();
        assertTrue(
                cause.getMessage() != null && cause.getMessage().contains(DECIDER),
                "the refusal must name the method that moved, got: " + cause.getMessage());
    }

    // ---------------------------------------------------------------- behaviour

    /**
     * The anchor control: the unpatched engine really does shorten idle wandering in rain, and
     * lengthen it when dry. Everything else in this class is only meaningful while this holds.
     */
    @Test
    void vanillaControlShortensTheIntervalInRain() throws Exception {
        Harness vanilla = new Harness(readClassBytes());

        RealWeather.rain();
        Range raining = vanilla.sample(SAMPLES);
        assertTrue(
                raining.min >= RAW_MIN && raining.max <= RAW_MAX,
                "vanilla raining picks must be the raw 400-999 range, got " + raining);

        RealWeather.dry();
        Range dry = vanilla.sample(SAMPLES);
        assertTrue(
                dry.min >= DRY_MIN && dry.max <= DRY_MAX,
                "vanilla dry picks must be the 1.5x range, got " + dry);
        assertTrue(
                dry.mean > raining.mean * 1.4f,
                "rain must measurably shorten the vanilla interval: dry " + dry + " vs " + raining);
    }

    /** At 150% a raining interval is the dry distribution exactly, so a storm costs nothing. */
    @Test
    void rainAtOneFiftyMatchesTheDryDistribution() throws Exception {
        GameServer.server = true;
        ZombieRainWanderInterval.setPercent(ZombieRainWanderInterval.RAIN_AS_DRY_PERCENT);
        RealWeather.rain();

        Range patched = new Harness(patched()).sample(SAMPLES);
        assertTrue(
                patched.min >= DRY_MIN && patched.max <= DRY_MAX,
                "patched raining picks must be the dry range, got " + patched);

        RealWeather.dry();
        Range vanillaDry = new Harness(readClassBytes()).sample(SAMPLES);
        assertTrue(
                Math.abs(patched.mean - vanillaDry.mean) < 40f,
                "patched rain and vanilla dry must agree on the mean: "
                        + patched
                        + " vs "
                        + vanillaDry);
        assertEquals(SAMPLES, ZombieRainWanderMetrics.rainScaled, "every raining pick is counted");
        assertTrue(ZombieRainWanderMetrics.dry <= SAMPLES, "dry picks counted separately");
    }

    /**
     * Half the correction: 125% lands strictly between the two vanilla distributions. Both ends are
     * measured here rather than hardcoded, so the case stays honest if the engine ever moves the
     * 400-1000 range or the 1.5x multiplier.
     */
    @Test
    void anIntermediatePercentLandsBetweenTheTwo() throws Exception {
        Harness vanilla = new Harness(readClassBytes());
        RealWeather.rain();
        Range vanillaRaining = vanilla.sample(SAMPLES);
        RealWeather.dry();
        Range vanillaDry = vanilla.sample(SAMPLES);

        GameServer.server = true;
        ZombieRainWanderInterval.setPercent(125);
        RealWeather.rain();
        Range patched = new Harness(patched()).sample(SAMPLES);

        assertTrue(
                patched.min >= RAW_MIN * 1.25f && patched.max <= RAW_MAX * 1.25f,
                "125% must scale the raw range by 1.25, got " + patched);
        assertTrue(
                patched.mean > vanillaRaining.mean * 1.2f,
                "must be well above vanilla's raining mean: " + patched + " vs " + vanillaRaining);
        assertTrue(
                patched.mean < vanillaDry.mean * 0.9f,
                "must stay short of vanilla's dry mean: " + patched + " vs " + vanillaDry);
    }

    /**
     * ⭐ Snow is not rain. {@code ClimateManager.isRaining()} excludes it explicitly, so a blizzard
     * already gets the long dry interval and this patch must leave it there.
     */
    @Test
    void snowIsNotRainAndIsLeftAlone() throws Exception {
        GameServer.server = true;
        ZombieRainWanderInterval.setPercent(ZombieRainWanderInterval.RAIN_AS_DRY_PERCENT);
        RealWeather.snow();

        Range patched = new Harness(patched()).sample(SAMPLES);
        assertTrue(
                patched.min >= DRY_MIN && patched.max <= DRY_MAX,
                "a snowstorm already gets vanilla's dry interval, got " + patched);
        assertEquals(
                0,
                ZombieRainWanderMetrics.rainScaled,
                "snow must not be counted as rain, or the scaling would apply twice over");
        assertEquals(SAMPLES, ZombieRainWanderMetrics.dry, "snow is counted as dry");
    }

    /** The default must be vanilla bit for bit, and must not even read the weather. */
    @Test
    void vanillaDefaultChangesNothing() throws Exception {
        GameServer.server = true;
        RealWeather.rain();
        Range patched = new Harness(patched()).sample(SAMPLES);
        assertTrue(
                patched.min >= RAW_MIN && patched.max <= RAW_MAX,
                "at the default the patched class must behave exactly as vanilla, got " + patched);
        assertEquals(0, ZombieRainWanderMetrics.rainScaled);
        assertEquals(0, ZombieRainWanderMetrics.dry);
    }

    @Test
    void clientPathIsUntouched() throws Exception {
        GameServer.server = false;
        ZombieRainWanderInterval.setPercent(ZombieRainWanderInterval.RAIN_AS_DRY_PERCENT);
        RealWeather.rain();
        Range patched = new Harness(patched()).sample(SAMPLES);
        assertTrue(
                patched.min >= RAW_MIN && patched.max <= RAW_MAX,
                "off the server the advice must change nothing, got " + patched);
        assertEquals(0, ZombieRainWanderMetrics.rainScaled);
    }

    @Test
    void aLatchedFailureRestoresVanillaIntervals() throws Exception {
        GameServer.server = true;
        ZombieRainWanderInterval.setPercent(ZombieRainWanderInterval.RAIN_AS_DRY_PERCENT);
        ZombieRainWanderInterval.latchForTest();
        RealWeather.rain();
        Range patched = new Harness(patched()).sample(SAMPLES);
        assertTrue(
                patched.min >= RAW_MIN && patched.max <= RAW_MAX,
                "a latched failure must fall back to vanilla, got " + patched);
        assertEquals(0, ZombieRainWanderMetrics.rainScaled);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] patched() throws Exception {
        return new ZombieRainWanderPatch().transform(readClassBytes());
    }

    /** Min, max and mean of a batch of picks, for range assertions on a random distribution. */
    private record Range(float min, float max, float mean) {
        @Override
        public String toString() {
            return String.format("[%.1f..%.1f mean %.1f]", min, max, mean);
        }
    }

    /**
     * Loads the idle state under test parent-last and drives its private wander pick through
     * reflection, exactly as {@code enter()} and {@code execute()} would call it.
     */
    private static final class Harness extends ClassLoader {
        private final byte[] target;
        private final Object state;
        private final Method decider;

        Harness(byte[] target) throws Exception {
            super(ZombieRainWanderPatchTest.class.getClassLoader());
            this.target = target;
            Class<?> c = loadClass(TARGET);
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            this.state = ctor.newInstance();
            this.decider = c.getDeclaredMethod(DECIDER);
            this.decider.setAccessible(true);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (TARGET.equals(name)) {
                Class<?> already = findLoadedClass(name);
                if (already != null) {
                    return already;
                }
                return defineClass(name, target, 0, target.length);
            }
            return super.loadClass(name, resolve);
        }

        Range sample(int n) throws Exception {
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            double total = 0;
            for (int i = 0; i < n; i++) {
                float v = (Float) decider.invoke(state);
                min = Math.min(min, v);
                max = Math.max(max, v);
                total += v;
            }
            return new Range(min, max, (float) (total / n));
        }
    }

    private static byte[] readClassBytes() throws Exception {
        try (InputStream is =
                ZombieRainWanderPatchTest.class.getClassLoader().getResourceAsStream(TARGET_RES)) {
            assertNotNull(is, TARGET_RES + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /**
     * The declaration only: enough for {@code TypePool.describe} to stop finding the method, which
     * is the condition the patch's shape check exists for. Call sites are left dangling on purpose;
     * the class is never loaded.
     */
    private static byte[] renameDecider(byte[] classBytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                String renamed = DECIDER.equals(name) ? "pickSomethingElse" : name;
                                return super.visitMethod(
                                        access, renamed, descriptor, signature, exceptions);
                            }
                        },
                        0);
        return writer.toByteArray();
    }

    /** INVOKESTATIC calls into the helper, in {@code method} (or anywhere when null). */
    private static int helperCalls(byte[] classBytes, String method) {
        int[] count = {0};
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (method != null && !method.equals(name)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && HELPER.equals(owner)
                                                && "adjust".equals(mName)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }
}
