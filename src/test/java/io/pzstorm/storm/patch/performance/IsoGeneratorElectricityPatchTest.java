package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.IntegrationTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link IsoGeneratorElectricityPatch} inserts the {@code totalPowerUsing <= 0}
 * guard at the head of {@code IsoGenerator.update()} so the advice falls through to the original
 * {@code setSurroundingElectricity()} when the field is uninitialized. Without this guard, fuel
 * stays at 100% forever after a world load because {@code totalPowerUsing} is not persisted by
 * {@code save()/load()} and the hourly fuel loop multiplies by it.
 *
 * <p>The patch is applied to the real {@code IsoGenerator.class} pulled from the test classpath
 * (via {@code projectzomboid.jar}). We compare the unpatched and patched bytecode to confirm the
 * advice contributes exactly one extra {@code GETFIELD} read of {@code totalPowerUsing} (the new
 * field-binding parameter) and that the original {@code setSurroundingElectricity()} call site
 * inside {@code update()} is preserved &mdash; the patch must not delete it, the guard must just
 * route to it on the cold path.
 *
 * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
 * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class IsoGeneratorElectricityPatchTest implements IntegrationTest {

    private static final String ISO_GENERATOR = "zombie/iso/objects/IsoGenerator";
    private static final String TOTAL_POWER_USING_FIELD = "totalPowerUsing";
    private static final String TOTAL_POWER_USING_DESC = "F";
    private static final String UPDATE_SURROUNDING_FIELD = "updateSurrounding";
    private static final String SET_SURROUNDING_ELECTRICITY = "setSurroundingElectricity";

    @Test
    void patchInsertsTotalPowerUsingGuardWithoutRemovingOriginalCall() throws Exception {
        byte[] rawClass = readClass(ISO_GENERATOR);

        Counts before = countInUpdate(rawClass);
        // Sanity: the original update() reads totalPowerUsing exactly once for its hourly fuel
        // loop. If this changes, the assertion below comparing patched-vs-original needs to be
        // re-derived.
        assertEquals(
                1,
                before.totalPowerUsingReads,
                "Pre-patch update() should read totalPowerUsing exactly once (hourly fuel loop);"
                        + " got "
                        + before.totalPowerUsingReads);
        assertEquals(
                1,
                before.setSurroundingElectricityCalls,
                "Pre-patch update() should call setSurroundingElectricity() exactly once; got "
                        + before.setSurroundingElectricityCalls);

        byte[] transformed = new IsoGeneratorElectricityPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Counts after = countInUpdate(transformed);

        // The advice binds totalPowerUsing via @Advice.FieldValue, which compiles to one extra
        // GETFIELD at the top of update(). One read in the original fuel loop + one read for the
        // advice parameter = 2 total.
        assertEquals(
                before.totalPowerUsingReads + 1,
                after.totalPowerUsingReads,
                "Patched update() should read totalPowerUsing one more time than the original"
                        + " (advice binds it via @Advice.FieldValue); before="
                        + before.totalPowerUsingReads
                        + " after="
                        + after.totalPowerUsingReads);

        // The original setSurroundingElectricity() call site in update() must remain intact —
        // when the guard fires (totalPowerUsing == 0), control returns from advice into the
        // original method, which then takes the slow path and initializes totalPowerUsing.
        assertEquals(
                before.setSurroundingElectricityCalls,
                after.setSurroundingElectricityCalls,
                "Patched update() must preserve the original setSurroundingElectricity() call;"
                        + " before="
                        + before.setSurroundingElectricityCalls
                        + " after="
                        + after.setSurroundingElectricityCalls);

        // The advice still reads updateSurrounding as a read/write @Advice.FieldValue — assert at
        // least one read remains so a future refactor that drops the binding is caught here
        // rather than silently regressing the chunk-bookkeeping path.
        assertTrue(
                after.updateSurroundingReads >= before.updateSurroundingReads,
                "Patched update() should read updateSurrounding at least as often as the original;"
                        + " before="
                        + before.updateSurroundingReads
                        + " after="
                        + after.updateSurroundingReads);
    }

    private static byte[] readClass(String internalName) throws Exception {
        String resourcePath = internalName + ".class";
        try (InputStream is =
                IsoGeneratorElectricityPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(resourcePath)) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static Counts countInUpdate(byte[] classBytes) {
        Counts counts = new Counts();
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
                                if (!"update".equals(name) || !"()V".equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        if (opcode != Opcodes.GETFIELD) {
                                            return;
                                        }
                                        if (!ISO_GENERATOR.equals(owner)) {
                                            return;
                                        }
                                        if (TOTAL_POWER_USING_FIELD.equals(fName)
                                                && TOTAL_POWER_USING_DESC.equals(fDesc)) {
                                            counts.totalPowerUsingReads++;
                                        }
                                        if (UPDATE_SURROUNDING_FIELD.equals(fName)) {
                                            counts.updateSurroundingReads++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (ISO_GENERATOR.equals(owner)
                                                && SET_SURROUNDING_ELECTRICITY.equals(mName)) {
                                            counts.setSurroundingElectricityCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class Counts {
        int totalPowerUsingReads;
        int updateSurroundingReads;
        int setSurroundingElectricityCalls;
    }
}
