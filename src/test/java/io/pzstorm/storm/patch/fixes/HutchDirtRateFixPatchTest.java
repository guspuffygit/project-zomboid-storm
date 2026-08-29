package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Verifies that {@link HutchDirtRateFixPatch} weaves the dirt-rate helper calls into {@code
 * IsoHutch.update()} and only there, and unit-tests the pure probability math and rate-percent
 * clamping in {@link HutchDirtRateFix}.
 */
class HutchDirtRateFixPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/iso/objects/IsoHutch";
    private static final String HELPER_CLASS = "io/pzstorm/storm/patch/fixes/HutchDirtRateFix";

    private static final String TARGET_METHOD = "update";
    private static final String TARGET_DESC = "()V";

    @Test
    void patchInjectsHelperIntoUpdateOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new HutchDirtRateFixPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(
                0,
                countHelperCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla update() must not reference the Storm helper");
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 2,
                "Patched update() must call " + HELPER_CLASS + " on entry and exit");
        assertEquals(
                0,
                countHelperCalls(transformed, "doMeta", null),
                "Advice must not leak into doMeta — the metagame rate is already correct");
        assertEquals(
                0,
                countHelperCalls(transformed, "updateAnimalInside", null),
                "Advice must not leak into the private updateAnimalInside helper");
    }

    @Test
    void baseProbMatchesDoMetaClamp() {
        // doMeta: prob = 25 - animals, clamped down to 10 — 1-in-10 per game hour for any
        // ordinary flock size.
        assertEquals(10, HutchDirtRateFix.effectiveProb(1, 100));
        assertEquals(10, HutchDirtRateFix.effectiveProb(6, 100));
        assertEquals(10, HutchDirtRateFix.effectiveProb(15, 100));
        assertEquals(9, HutchDirtRateFix.effectiveProb(16, 100));
        assertEquals(1, HutchDirtRateFix.effectiveProb(24, 100));
        // Overcrowded hutches floor at certainty instead of NextBool(0)/negative.
        assertEquals(1, HutchDirtRateFix.effectiveProb(30, 100));
    }

    @Test
    void ratePercentScalesInverseProbability() {
        assertEquals(20, HutchDirtRateFix.effectiveProb(6, 50));
        assertEquals(5, HutchDirtRateFix.effectiveProb(6, 200));
        assertEquals(1, HutchDirtRateFix.effectiveProb(6, 1000));
        // Never below 1 even when percent over-scales a small base.
        assertEquals(1, HutchDirtRateFix.effectiveProb(24, 1000));
    }

    @Test
    void setRatePercentClampsAndReturnsApplied() {
        try {
            assertEquals(100, HutchDirtRateFix.setRatePercent(100));
            assertEquals(0, HutchDirtRateFix.setRatePercent(-5));
            assertEquals(1000, HutchDirtRateFix.setRatePercent(5000));
            assertEquals(250, HutchDirtRateFix.setRatePercent(250));
            assertEquals(250, HutchDirtRateFix.getRatePercent());
        } finally {
            HutchDirtRateFix.setRatePercent(HutchDirtRateFix.DEFAULT_RATE_PERCENT);
        }
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCalls(byte[] classBytes, String method, String desc) {
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
                                if (!method.equals(name)
                                        || (desc != null && !desc.equals(descriptor))) {
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
                                        if (HELPER_CLASS.equals(owner)) {
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
