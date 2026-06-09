package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.utils.UpdateLimit;

/**
 * Verifies the tick-rate patch's factory logic and that the patched {@code GameServer} bytecode
 * actually rewrites the {@code new UpdateLimit(100L)} call (and the in-loop {@code Check()} calls)
 * inside {@code main}, and only inside {@code main}.
 */
class GameServerTickRatePatchTest implements UnitTest {

    private long savedLogWindow;

    @BeforeEach
    void captureState() {
        savedLogWindow = UpdateLimitFactory.logWindowNanos;
        // Disable per-window logging in unit tests by default.
        UpdateLimitFactory.logWindowNanos = Long.MAX_VALUE;
        UpdateLimitFactory.resetTickCounterForTest();
        UpdateLimitFactory.clearServerTickLimiterForTest();
    }

    @AfterEach
    void restoreState() {
        UpdateLimitFactory.logWindowNanos = savedLogWindow;
        UpdateLimitFactory.resetTickCounterForTest();
        UpdateLimitFactory.clearServerTickLimiterForTest();
    }

    // -------- create() --------

    @Test
    void createPassesThroughNonDefaultDelays() {
        // The static-field UpdateLimits use 1000L and 2000L; these must NOT be remapped even
        // if someone happened to call the factory directly with those values.
        UpdateLimit limit1 = UpdateLimitFactory.create(1000L);
        UpdateLimit limit2 = UpdateLimitFactory.create(2000L);
        assertEquals(1000L, limit1.getDelay());
        assertEquals(2000L, limit2.getDelay());
    }

    @Test
    void createUsesVanillaDelay() {
        UpdateLimit limit = UpdateLimitFactory.create(100L);
        assertEquals(GameServerTickRatePatch.DEFAULT_TICK_INTERVAL_MS, limit.getDelay());
        assertEquals(
                GameServerTickRatePatch.DEFAULT_TICK_INTERVAL_MS,
                UpdateLimitFactory.getCurrentTickIntervalMs());
    }

    @Test
    void createCapturesServerTickLimiterReference() {
        UpdateLimit limit = UpdateLimitFactory.create(100L);
        assertSame(limit, UpdateLimitFactory.serverTickLimiterForTest());
    }

    @Test
    void isLimiterReadyTracksInstall() {
        UpdateLimitFactory.clearServerTickLimiterForTest();
        assertFalse(UpdateLimitFactory.isLimiterReady());
        UpdateLimitFactory.create(100L);
        assertTrue(UpdateLimitFactory.isLimiterReady());
    }

    // -------- checkAndCount() --------

    @Test
    void checkAndCountReturnsCheckResult() {
        // Fresh limit with default delay won't fire on the first immediate Check().
        UpdateLimit fresh = UpdateLimitFactory.create(100L);
        assertFalse(UpdateLimitFactory.checkAndCount(fresh));
        assertEquals(0, UpdateLimitFactory.observedTicksForTest());
    }

    @Test
    void checkAndCountIncrementsForServerLimiter() throws InterruptedException {
        UpdateLimit serverLimiter = UpdateLimitFactory.create(100L);
        UpdateLimitFactory.setTickIntervalMs(5L);
        Thread.sleep(20);
        assertTrue(UpdateLimitFactory.checkAndCount(serverLimiter));
        assertEquals(1, UpdateLimitFactory.observedTicksForTest());
    }

    @Test
    void checkAndCountIgnoresLimitersOtherThanServer() throws InterruptedException {
        UpdateLimitFactory.create(100L); // installs a server-tick reference
        UpdateLimit foreign = new UpdateLimit(1L);
        Thread.sleep(20);
        assertTrue(UpdateLimitFactory.checkAndCount(foreign));
        assertEquals(0, UpdateLimitFactory.observedTicksForTest());
    }

    @Test
    void checkAndCountResetsCounterWhenWindowElapses() throws InterruptedException {
        UpdateLimitFactory.logWindowNanos = 1L; // any positive elapsed nanos triggers a flush
        UpdateLimit serverLimiter = UpdateLimitFactory.create(100L);
        UpdateLimitFactory.setTickIntervalMs(5L);
        Thread.sleep(20);
        UpdateLimitFactory.checkAndCount(serverLimiter);
        // After firing, the counter should reset.
        assertEquals(0, UpdateLimitFactory.observedTicksForTest());
    }

    // -------- setTickIntervalMs() --------

    @Test
    void setTickIntervalMsThrowsWhenLimiterNotInstalled() {
        UpdateLimitFactory.clearServerTickLimiterForTest();
        assertThrows(IllegalStateException.class, () -> UpdateLimitFactory.setTickIntervalMs(50L));
    }

    @Test
    void setTickIntervalMsUpdatesLiveLimiterDelay() {
        UpdateLimit limit = UpdateLimitFactory.create(100L);
        assertEquals(100L, limit.getDelay());

        long applied = UpdateLimitFactory.setTickIntervalMs(33L);
        assertEquals(33L, applied);
        assertEquals(33L, limit.getDelay());
        assertEquals(33L, UpdateLimitFactory.getCurrentTickIntervalMs());
        // The same UpdateLimit instance is mutated in place — handler does not swap references.
        assertSame(limit, UpdateLimitFactory.serverTickLimiterForTest());
    }

    @Test
    void setTickIntervalMsClampsBelowMinimum() {
        UpdateLimit limit = UpdateLimitFactory.create(100L);
        long applied = UpdateLimitFactory.setTickIntervalMs(-1L);
        assertEquals(GameServerTickRatePatch.MIN_TICK_INTERVAL_MS, applied);
        assertEquals(GameServerTickRatePatch.MIN_TICK_INTERVAL_MS, limit.getDelay());
        assertEquals(
                GameServerTickRatePatch.MIN_TICK_INTERVAL_MS,
                UpdateLimitFactory.getCurrentTickIntervalMs());
    }

    @Test
    void setTickIntervalMsClampsAboveMaximum() {
        UpdateLimit limit = UpdateLimitFactory.create(100L);
        long applied = UpdateLimitFactory.setTickIntervalMs(999_999L);
        assertEquals(GameServerTickRatePatch.MAX_TICK_INTERVAL_MS, applied);
        assertEquals(GameServerTickRatePatch.MAX_TICK_INTERVAL_MS, limit.getDelay());
        assertEquals(
                GameServerTickRatePatch.MAX_TICK_INTERVAL_MS,
                UpdateLimitFactory.getCurrentTickIntervalMs());
    }

    @Test
    void setTickIntervalMsResetsTickCounter() throws InterruptedException {
        UpdateLimit limit = UpdateLimitFactory.create(100L);
        UpdateLimitFactory.setTickIntervalMs(5L);
        Thread.sleep(20);
        UpdateLimitFactory.checkAndCount(limit);
        assertEquals(1, UpdateLimitFactory.observedTicksForTest());

        UpdateLimitFactory.setTickIntervalMs(50L);
        assertEquals(0, UpdateLimitFactory.observedTicksForTest());
    }

    @Test
    void getCurrentTickIntervalMsReflectsLiveSetter() {
        UpdateLimitFactory.create(100L);
        UpdateLimitFactory.setTickIntervalMs(40L);
        assertEquals(40L, UpdateLimitFactory.getCurrentTickIntervalMs());
    }

    // -------- bytecode --------

    /**
     * Apply the patch to the real {@code GameServer.class} and confirm both substitutions: the
     * constructor call inside {@code main} is rewritten to {@code INVOKESTATIC} on {@link
     * UpdateLimitFactory#create(long)}, and every {@code UpdateLimit.Check()} call inside {@code
     * main} is rewritten to {@code INVOKESTATIC} on {@link UpdateLimitFactory#checkAndCount}. No
     * {@code <clinit>} call sites are touched.
     *
     * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
     * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
     */
    @Test
    void patchRewritesOnlyTheMainLoopCallSites() throws Exception {
        String className = "zombie.network.GameServer";
        String resourcePath = className.replace('.', '/') + ".class";

        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "GameServer.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new GameServerTickRatePatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        InvocationCounts counts = countInvocations(transformed);

        // After patching, main() should contain exactly one INVOKESTATIC to the factory and
        // zero remaining INVOKESPECIAL calls to UpdateLimit.<init>.
        assertEquals(
                1,
                counts.mainFactoryCalls,
                "main() should contain exactly one INVOKESTATIC to UpdateLimitFactory.create");
        assertEquals(
                0,
                counts.mainCtorCalls,
                "main() should not contain any direct INVOKESPECIAL on UpdateLimit.<init>");

        // All UpdateLimit.Check() calls in main() must have been rewritten to checkAndCount.
        assertEquals(
                0,
                counts.mainCheckCalls,
                "main() should have no remaining INVOKEVIRTUAL on UpdateLimit.Check after rewrite");
        assertTrue(
                counts.mainCheckAndCountCalls >= 1,
                "main() should contain at least one INVOKESTATIC to UpdateLimitFactory.checkAndCount"
                        + " (got "
                        + counts.mainCheckAndCountCalls
                        + ")");

        // <clinit> initializes the static-field UpdateLimits (1000L / 2000L delays). Those
        // call sites are outside the main(String[]) substitution scope and must remain raw
        // INVOKESPECIAL constructor calls.
        assertEquals(
                0,
                counts.clinitFactoryCalls,
                "<clinit> must not be rewritten — only main() is in scope");
        assertTrue(
                counts.clinitCtorCalls >= 2,
                "<clinit> should still construct the static-field UpdateLimits directly (got "
                        + counts.clinitCtorCalls
                        + ")");
    }

    private static InvocationCounts countInvocations(byte[] classBytes) {
        InvocationCounts counts = new InvocationCounts();
        String factoryInternal = UpdateLimitFactory.class.getName().replace('.', '/');
        String updateLimitInternal = "zombie/core/utils/UpdateLimit";

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
                                boolean isMain =
                                        "main".equals(name)
                                                && "([Ljava/lang/String;)V".equals(descriptor);
                                boolean isClinit =
                                        "<clinit>".equals(name) && "()V".equals(descriptor);
                                if (!isMain && !isClinit) {
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
                                                && owner.equals(factoryInternal)
                                                && mName.equals("create")
                                                && mDesc.equals(
                                                        "(J)L" + updateLimitInternal + ";")) {
                                            if (isMain) counts.mainFactoryCalls++;
                                            else counts.clinitFactoryCalls++;
                                        }
                                        if (opcode == Opcodes.INVOKESPECIAL
                                                && owner.equals(updateLimitInternal)
                                                && mName.equals("<init>")) {
                                            if (isMain) counts.mainCtorCalls++;
                                            else counts.clinitCtorCalls++;
                                        }
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && owner.equals(factoryInternal)
                                                && mName.equals("checkAndCount")
                                                && mDesc.equals(
                                                        "(L" + updateLimitInternal + ";)Z")) {
                                            if (isMain) counts.mainCheckAndCountCalls++;
                                        }
                                        if (opcode == Opcodes.INVOKEVIRTUAL
                                                && owner.equals(updateLimitInternal)
                                                && mName.equals("Check")
                                                && mDesc.equals("()Z")) {
                                            if (isMain) counts.mainCheckCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class InvocationCounts {
        int mainFactoryCalls;
        int mainCtorCalls;
        int mainCheckCalls;
        int mainCheckAndCountCalls;
        int clinitFactoryCalls;
        int clinitCtorCalls;
    }
}
