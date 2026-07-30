package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.IntegrationTest;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link IsoGeneratorElectricityPatch} inserts the {@code totalPowerUsing <= 0} guard
 * at the head of {@code IsoGenerator.update()} so the advice falls through to the original {@code
 * setSurroundingElectricity()} when the field is uninitialized. Without this guard, fuel stays at
 * 100% forever after a world load because {@code totalPowerUsing} is not persisted by {@code
 * save()/load()} and the hourly fuel loop multiplies by it.
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

    /**
     * SHA-256 of the normalized instruction stream of the vanilla methods whose logic {@code
     * SkipServerScanAdvice.onEnter} inlines as a hand-written copy (they are private, so nothing
     * compile-checks the copy): {@code touchesChunk(IsoChunk)}'s chunk box test and {@code
     * setGeneratorRange()}'s {@code generatorRadius / 8 + 1} chunk range. If either fingerprint
     * changes on a game update, the copies must be re-verified line by line against the new source
     * before updating the constant — a silent divergence leaves edge chunks without {@code
     * addGeneratorPos} bookkeeping (squares that should have power report none).
     */
    private static final String TOUCHES_CHUNK_FINGERPRINT =
            "4c18636e0fdd3f54678ee9a11495b079732b1d9437ae35da146626d7bb755ffa";

    private static final String SET_GENERATOR_RANGE_FINGERPRINT =
            "28215c2468118e1537dbe68c37ccb8c7774174f95782bf9197a58d5bdb15385d";

    @Test
    void copiedVanillaLogicIsByteIdentical() throws Exception {
        byte[] rawClass = readClass(ISO_GENERATOR);
        assertEquals(
                TOUCHES_CHUNK_FINGERPRINT,
                fingerprintOfMethods(rawClass, "touchesChunk"),
                "Vanilla IsoGenerator.touchesChunk changed. SkipServerScanAdvice.onEnter inlines"
                        + " a copy of its chunk box test (the minX/maxX/minY/maxY bounds checks);"
                        + " re-verify the inlined copy against the new decompiled source, then"
                        + " update TOUCHES_CHUNK_FINGERPRINT.");
        assertEquals(
                SET_GENERATOR_RANGE_FINGERPRINT,
                fingerprintOfMethods(rawClass, "setGeneratorRange"),
                "Vanilla IsoGenerator.setGeneratorRange changed. SkipServerScanAdvice.onEnter"
                        + " inlines its 'generatorRadius / 8 + 1' chunk-range formula; re-verify"
                        + " the inlined copy against the new decompiled source, then update"
                        + " SET_GENERATOR_RANGE_FINGERPRINT.");
    }

    /**
     * Normalized instruction-stream fingerprint of every method named {@code methodName} (any
     * descriptor): opcodes with operands, labels numbered by first appearance, no debug info or
     * frames. Stable across recompiles of unchanged source; changes when the method's logic does.
     */
    private static String fingerprintOfMethods(byte[] classBytes, String methodName)
            throws Exception {
        StringBuilder stream = new StringBuilder();
        int[] found = new int[1];
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
                                if (!methodName.equals(name)) {
                                    return null;
                                }
                                found[0]++;
                                stream.append(name).append(descriptor).append('\n');
                                return new InstructionRecorder(stream);
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        assertTrue(
                found[0] > 0,
                "IsoGenerator no longer declares a method named '"
                        + methodName
                        + "' — the logic copied into SkipServerScanAdvice has no vanilla"
                        + " counterpart to verify against; re-derive the advice from the new"
                        + " source.");
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest(
                                stream.toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** Records each instruction as one line; labels get stable ids by first appearance. */
    private static final class InstructionRecorder extends MethodVisitor {

        private final StringBuilder out;
        private final Map<Label, Integer> labelIds = new HashMap<>();

        InstructionRecorder(StringBuilder out) {
            super(Opcodes.ASM9);
            this.out = out;
        }

        private String labelId(Label label) {
            return "L" + labelIds.computeIfAbsent(label, l -> labelIds.size());
        }

        @Override
        public void visitInsn(int opcode) {
            out.append(opcode).append('\n');
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            out.append(opcode).append(' ').append(operand).append('\n');
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            out.append(opcode).append(" v").append(varIndex).append('\n');
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            out.append(opcode).append(' ').append(type).append('\n');
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            out.append(opcode)
                    .append(' ')
                    .append(owner)
                    .append('.')
                    .append(name)
                    .append(':')
                    .append(descriptor)
                    .append('\n');
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            out.append(opcode)
                    .append(' ')
                    .append(owner)
                    .append('.')
                    .append(name)
                    .append(descriptor)
                    .append('\n');
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name, String descriptor, Handle handle, Object... args) {
            out.append("indy ").append(name).append(descriptor).append('\n');
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            out.append(opcode).append(' ').append(labelId(label)).append('\n');
        }

        @Override
        public void visitLabel(Label label) {
            out.append(labelId(label)).append(":\n");
        }

        @Override
        public void visitLdcInsn(Object value) {
            out.append("ldc ").append(value).append('\n');
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            out.append("iinc v").append(varIndex).append(' ').append(increment).append('\n');
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            out.append("tableswitch ").append(min).append('-').append(max);
            out.append(' ').append(labelId(dflt));
            for (Label label : labels) {
                out.append(' ').append(labelId(label));
            }
            out.append('\n');
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            out.append("lookupswitch ").append(labelId(dflt));
            for (int i = 0; i < keys.length; i++) {
                out.append(' ').append(keys[i]).append("->").append(labelId(labels[i]));
            }
            out.append('\n');
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            out.append("multianewarray ")
                    .append(descriptor)
                    .append(' ')
                    .append(numDimensions)
                    .append('\n');
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            out.append("trycatch ")
                    .append(labelId(start))
                    .append(' ')
                    .append(labelId(end))
                    .append(' ')
                    .append(labelId(handler))
                    .append(' ')
                    .append(type)
                    .append('\n');
        }
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
