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

/** Checks the coordinated advice/substitution and retained native fallback against game bytes. */
class CorpseCountZombieIndexPatchTest implements UnitTest {
    private static final String BODY = "getCorpseCount(IIILzombie/iso/areas/IsoBuilding;)I";
    private static final String HELPER = "io/pzstorm/storm/spatial/StormCorpseZombieCount";

    @Test
    void nativeBodyKeepsSquareFallbackAndOnlyBodyOverloadReceivesHooks() throws Exception {
        byte[] raw;
        try (InputStream input =
                getClass().getClassLoader().getResourceAsStream("zombie/iso/CorpseCount.class")) {
            assertNotNull(input);
            raw = input.readAllBytes();
        }
        Map<String, int[]> nativeCounts = calls(raw);
        assertEquals(1, nativeCounts.get(BODY)[2], "one native zombie square counter call site");
        byte[] patched = new CorpseCountZombieIndexPatch().transform(raw);
        assertNotNull(patched);
        Map<String, int[]> transformed = calls(patched);
        assertEquals(1, transformed.get(BODY)[0], "one option-read substitution");
        // Advice is inlined at each native return, including the early corpse-cap return.
        assertTrue(transformed.get(BODY)[1] >= 1, "exit advice must serve the count");
        assertEquals(
                nativeCounts.get(BODY)[2],
                transformed.get(BODY)[2],
                "the native square scan remains available when the index is not ready");
        for (Map.Entry<String, int[]> entry : transformed.entrySet()) {
            if (!entry.getKey().equals(BODY)) {
                assertEquals(
                        0, entry.getValue()[0], "substitution must not touch another overload");
                assertEquals(
                        0, entry.getValue()[1], "augmentation must not touch another overload");
            }
        }
    }

    private static Map<String, int[]> calls(byte[] bytes) {
        Map<String, int[]> counts = new HashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                int[] count = new int[3];
                                counts.put(name + descriptor, count);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String name,
                                            String descriptor,
                                            boolean isInterface) {
                                        if (owner.equals(HELPER)
                                                && name.equals("readZombieHealthImpact"))
                                            count[0]++;
                                        if (owner.equals(HELPER) && name.equals("augment"))
                                            count[1]++;
                                        if (owner.equals("zombie/iso/IsoGridSquare")
                                                && name.equals("getZombieCount")) count[2]++;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return counts;
    }
}
