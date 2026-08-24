package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Weave-verifies {@code NetworkZombiePackerAuthPassPatch} against the real {@code
 * NetworkZombiePacker} class bytes: the pass bracket adds exactly one {@code
 * StormZombieAuthScan.beginPass} call and at least one {@code endPass} call (the exit advice covers
 * both the normal return and the throwable handler) to the private zero-argument {@code
 * updateAuth()}, keeps the vanilla per-zombie loop in place, and leaves every other method
 * untouched.
 */
class NetworkZombiePackerAuthPassPatchTest implements UnitTest {

    private static final String PACKER = "zombie/popman/NetworkZombiePacker";
    private static final String SCAN = "io/pzstorm/storm/zombie/StormZombieAuthScan";
    private static final String UPDATE_AUTH = "updateAuth()V";

    @Test
    void patchBracketsAuthPassAndKeepsVanillaLoop() throws Exception {
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(PACKER + ".class")) {
            assertNotNull(is, "NetworkZombiePacker.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new NetworkZombiePackerAuthPassPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Map<String, Counts> before = countPerMethod(rawClass);
        Map<String, Counts> after = countPerMethod(transformed);

        Counts updateBefore = before.get(UPDATE_AUTH);
        Counts updateAfter = after.get(UPDATE_AUTH);
        assertNotNull(updateBefore, "vanilla NetworkZombiePacker must declare updateAuth()");
        assertNotNull(updateAfter, "patched NetworkZombiePacker must declare updateAuth()");

        assertEquals(0, updateBefore.beginPassCalls, "vanilla body must not call beginPass");
        assertEquals(0, updateBefore.endPassCalls, "vanilla body must not call endPass");
        assertEquals(
                1,
                updateAfter.beginPassCalls,
                "the enter advice should add exactly one StormZombieAuthScan.beginPass call");
        assertTrue(
                updateAfter.endPassCalls >= 1,
                "the exit advice should add at least one StormZombieAuthScan.endPass call");
        assertEquals(
                updateBefore.managerUpdateAuthCalls,
                updateAfter.managerUpdateAuthCalls,
                "the vanilla per-zombie NetworkZombieManager.updateAuth loop must survive");
        assertTrue(
                updateAfter.managerUpdateAuthCalls >= 1,
                "updateAuth() should retain the vanilla per-zombie loop call");

        // The advice must not leak outside updateAuth().
        for (Map.Entry<String, Counts> entry : before.entrySet()) {
            if (UPDATE_AUTH.equals(entry.getKey())) {
                continue;
            }
            assertEquals(
                    entry.getValue(),
                    after.get(entry.getKey()),
                    "method " + entry.getKey() + " must be untouched by the patch");
        }
    }

    private static Map<String, Counts> countPerMethod(byte[] classBytes) {
        Map<String, Counts> counts = new HashMap<>();
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
                                Counts method =
                                        counts.computeIfAbsent(
                                                name + descriptor, k -> new Counts());
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if (SCAN.equals(owner)) {
                                            if ("beginPass".equals(mname)) {
                                                method.beginPassCalls++;
                                            }
                                            if ("endPass".equals(mname)) {
                                                method.endPassCalls++;
                                            }
                                        }
                                        if ("zombie/popman/NetworkZombieManager".equals(owner)
                                                && "updateAuth".equals(mname)) {
                                            method.managerUpdateAuthCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return counts;
    }

    private static final class Counts {
        int beginPassCalls;
        int endPassCalls;
        int managerUpdateAuthCalls;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Counts)) {
                return false;
            }
            Counts other = (Counts) o;
            return beginPassCalls == other.beginPassCalls
                    && endPassCalls == other.endPassCalls
                    && managerUpdateAuthCalls == other.managerUpdateAuthCalls;
        }

        @Override
        public int hashCode() {
            return beginPassCalls * 31 * 31 + endPassCalls * 31 + managerUpdateAuthCalls;
        }

        @Override
        public String toString() {
            return "beginPassCalls="
                    + beginPassCalls
                    + " endPassCalls="
                    + endPassCalls
                    + " managerUpdateAuthCalls="
                    + managerUpdateAuthCalls;
        }
    }
}
