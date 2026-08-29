package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ServerCellRecalcCrashGuardPatch} weaves the crash-guard helper call into
 * {@code ServerCell.RecalcAll2()} and only there, and unit-tests the pure swallow decision in
 * {@link ServerCellRecalcCrashGuard#shouldSwallow}.
 */
class ServerCellRecalcCrashGuardPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/network/ServerMap$ServerCell";
    private static final String HELPER_CLASS =
            "io/pzstorm/storm/patch/fixes/ServerCellRecalcCrashGuard";

    @Test
    void patchInjectsHelperIntoRecalcAll2Only() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new ServerCellRecalcCrashGuardPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCalls(rawClass, "RecalcAll2"),
                "Vanilla RecalcAll2 must not reference the Storm helper");
        assertTrue(
                countHelperCalls(transformed, "RecalcAll2") >= 1,
                "Patched RecalcAll2 must call " + HELPER_CLASS);
        assertEquals(
                0,
                countHelperCalls(transformed, "Load2"),
                "Advice must not leak into Load2 — eviction relies on the vanilla success path");
        assertEquals(
                0, countHelperCalls(transformed, "update"), "Advice must not leak into update");
    }

    @Test
    void patchStacksOnTopOfTheOtherServerCellTransformers() throws Exception {
        // Registration order in StormClassTransformers: Load2 timing, RecalcAll2 timing,
        // this guard, update timing. The guard is the first second-advice on RecalcAll2,
        // so weave the full chain the way the runtime does and make sure both survive.
        byte[] bytes = readClassBytes(TARGET_CLASS + ".class");
        bytes = new io.pzstorm.storm.patch.performance.ServerCellLoad2Patch().transform(bytes);
        bytes = new io.pzstorm.storm.patch.performance.ServerCellRecalcAll2Patch().transform(bytes);
        bytes = new ServerCellRecalcCrashGuardPatch().transform(bytes);
        bytes = new io.pzstorm.storm.patch.performance.ServerCellUpdatePatch().transform(bytes);
        assertNotNull(bytes);

        assertTrue(
                countHelperCalls(bytes, "RecalcAll2") >= 1,
                "Chained weave must keep the crash-guard call in RecalcAll2");
        assertTrue(
                countCalls(bytes, "RecalcAll2", "io/pzstorm/storm/metrics/MainLoopStepTimings")
                        >= 1,
                "Chained weave must keep the timing advice in RecalcAll2");
        assertEquals(0, countHelperCalls(bytes, "Load2"), "Guard must stay out of Load2");
    }

    @Test
    void recalcAll2ThrowSitesAreCovered() {
        // The class of throwable that froze ATF live 2026-08-29.
        assertTrue(
                ServerCellRecalcCrashGuard.shouldSwallow(
                        new IllegalArgumentException("Entity is already registered")));
        // Torn chunk data surfaces as AIOOBE (see pz-torn-chunk-buffer-crash).
        assertTrue(
                ServerCellRecalcCrashGuard.shouldSwallow(new ArrayIndexOutOfBoundsException(-1)));
        assertTrue(ServerCellRecalcCrashGuard.shouldSwallow(new NullPointerException()));
    }

    @Test
    void virtualMachineErrorsAreNeverMasked() {
        assertFalse(ServerCellRecalcCrashGuard.shouldSwallow(new OutOfMemoryError()));
        assertFalse(ServerCellRecalcCrashGuard.shouldSwallow(new StackOverflowError()));
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCalls(byte[] classBytes, String method) {
        return countCalls(classBytes, method, HELPER_CLASS);
    }

    private static int countCalls(byte[] classBytes, String method, String calleeOwner) {
        int[] hits = new int[1];
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
                                if (!method.equals(name)) {
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
                                        if (calleeOwner.equals(owner)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return hits[0];
    }
}
