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
 * Weave-verifies {@code ZombieAuthScanFastPathPatch} against the real {@code NetworkZombieManager}
 * class bytes: the enter advice adds exactly one {@code StormZombieAuthScan.updateAuthFast} call to
 * {@code updateAuth(IsoZombie)}, keeps the vanilla body — including its {@code moveZombie} call
 * sites — in place as the skipped-to path, and leaves every other method untouched.
 */
class ZombieAuthScanFastPathPatchTest implements UnitTest {

    private static final String MANAGER = "zombie/popman/NetworkZombieManager";
    private static final String SCAN = "io/pzstorm/storm/zombie/StormZombieAuthScan";
    private static final String UPDATE_AUTH = "updateAuth(Lzombie/characters/IsoZombie;)V";

    @Test
    void patchRoutesUpdateAuthThroughFastPathAndKeepsVanillaBody() throws Exception {
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MANAGER + ".class")) {
            assertNotNull(is, "NetworkZombieManager.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new ZombieAuthScanFastPathPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Map<String, Counts> before = countPerMethod(rawClass);
        Map<String, Counts> after = countPerMethod(transformed);

        Counts updateBefore = before.get(UPDATE_AUTH);
        Counts updateAfter = after.get(UPDATE_AUTH);
        assertNotNull(
                updateBefore, "vanilla NetworkZombieManager must declare updateAuth(IsoZombie)");
        assertNotNull(
                updateAfter, "patched NetworkZombieManager must declare updateAuth(IsoZombie)");

        assertEquals(
                0, updateBefore.updateAuthFastCalls, "vanilla body must not call the fast path");
        assertEquals(
                1,
                updateAfter.updateAuthFastCalls,
                "the enter advice should add exactly one StormZombieAuthScan.updateAuthFast call");
        assertEquals(
                updateBefore.moveZombieCalls,
                updateAfter.moveZombieCalls,
                "the vanilla moveZombie call sites must survive as the skipped-to path");
        assertTrue(
                updateAfter.moveZombieCalls >= 1,
                "updateAuth(IsoZombie) should retain the vanilla moveZombie calls");

        // The advice must not leak outside updateAuth(IsoZombie).
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
                                        if (SCAN.equals(owner) && "updateAuthFast".equals(mname)) {
                                            method.updateAuthFastCalls++;
                                        }
                                        if ("moveZombie".equals(mname)) {
                                            method.moveZombieCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return counts;
    }

    private static final class Counts {
        int updateAuthFastCalls;
        int moveZombieCalls;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Counts)) {
                return false;
            }
            Counts other = (Counts) o;
            return updateAuthFastCalls == other.updateAuthFastCalls
                    && moveZombieCalls == other.moveZombieCalls;
        }

        @Override
        public int hashCode() {
            return updateAuthFastCalls * 31 + moveZombieCalls;
        }

        @Override
        public String toString() {
            return "updateAuthFastCalls="
                    + updateAuthFastCalls
                    + " moveZombieCalls="
                    + moveZombieCalls;
        }
    }
}
