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
 * Weave-verifies {@code IsoThumpableUpdateSkipPatch} against the real {@code IsoThumpable} class
 * bytes: the inlined enter advice adds exactly one {@code GameServer.server} read and one {@code
 * getLifeLeft()} call to {@code update()} (the skip test), leaves every other method untouched, and
 * keeps the vanilla body — including the {@code getObjectIndex()} scan — in place for the
 * non-skipped path.
 */
class IsoThumpableUpdateSkipPatchTest implements UnitTest {

    private static final String ISO_THUMPABLE = "zombie/iso/objects/IsoThumpable";
    private static final String GAME_SERVER = "zombie/network/GameServer";

    @Test
    void patchAddsSkipTestOnlyToUpdateAndKeepsVanillaBody() throws Exception {
        byte[] rawClass;
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream(ISO_THUMPABLE + ".class")) {
            assertNotNull(is, "IsoThumpable.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new IsoThumpableUpdateSkipPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Map<String, Counts> before = countPerMethod(rawClass);
        Map<String, Counts> after = countPerMethod(transformed);

        Counts updateBefore = before.get("update()V");
        Counts updateAfter = after.get("update()V");
        assertNotNull(updateBefore, "vanilla IsoThumpable must declare update()");
        assertNotNull(updateAfter, "patched IsoThumpable must declare update()");

        assertEquals(
                updateBefore.serverReads + 1,
                updateAfter.serverReads,
                "the inlined skip test should add exactly one GameServer.server read to update()");
        assertEquals(
                updateBefore.getLifeLeftCalls + 1,
                updateAfter.getLifeLeftCalls,
                "the inlined skip test should add exactly one getLifeLeft() call to update()");
        assertEquals(
                updateBefore.getObjectIndexCalls,
                updateAfter.getObjectIndexCalls,
                "the vanilla getObjectIndex() body must survive as the non-skipped path");
        assertTrue(
                updateAfter.getObjectIndexCalls >= 1,
                "update() should retain the vanilla getObjectIndex() call");

        // The advice must not leak outside update().
        for (Map.Entry<String, Counts> entry : before.entrySet()) {
            if ("update()V".equals(entry.getKey())) {
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
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fname, String fdesc) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && GAME_SERVER.equals(owner)
                                                && "server".equals(fname)) {
                                            method.serverReads++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if ("getLifeLeft".equals(mname)) {
                                            method.getLifeLeftCalls++;
                                        }
                                        if ("getObjectIndex".equals(mname)) {
                                            method.getObjectIndexCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return counts;
    }

    private static final class Counts {
        int serverReads;
        int getLifeLeftCalls;
        int getObjectIndexCalls;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Counts)) {
                return false;
            }
            Counts other = (Counts) o;
            return serverReads == other.serverReads
                    && getLifeLeftCalls == other.getLifeLeftCalls
                    && getObjectIndexCalls == other.getObjectIndexCalls;
        }

        @Override
        public int hashCode() {
            return serverReads * 31 * 31 + getLifeLeftCalls * 31 + getObjectIndexCalls;
        }

        @Override
        public String toString() {
            return "serverReads="
                    + serverReads
                    + " getLifeLeftCalls="
                    + getLifeLeftCalls
                    + " getObjectIndexCalls="
                    + getObjectIndexCalls;
        }
    }
}
