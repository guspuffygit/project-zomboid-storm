package io.pzstorm.storm.patch.performance;

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
 * Asserts {@code IsoGridSquare.discard()} is instrumented to reset the LOS slots {@link
 * IsoGridSquareLosParallelPatch} adds beyond vanilla's four.
 *
 * <p>Vanilla's reset loop is bounded by the literal {@code 4}, so its {@code discard()} never reads
 * {@code lighting.length} — there is no {@code ARRAYLENGTH} in the method at all. The advice's loop
 * is bounded by {@code lighting.length}, so exactly one appears once the patch is applied. That
 * makes {@code ARRAYLENGTH} a falsifiable marker for "the advice was actually woven in": drop the
 * {@code Advice.to(DISCARD_ADVICE)} visit from the patch and this test fails.
 *
 * <p>Without the advice, a square recycled through {@code isoGridSquareCache} keeps the previous
 * location's {@code bCouldSee} bits in slots {@code 4..MAX-1} until the next {@code CalcVisibility}
 * for that specific slot.
 */
class IsoGridSquareDiscardAdviceTest implements UnitTest {

    private static final String ISO_GRID_SQUARE = "zombie/iso/IsoGridSquare";

    @Test
    void patchMakesDiscardResetEverySlotItAllocated() throws Exception {
        byte[] rawClass;
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream(ISO_GRID_SQUARE + ".class")) {
            assertNotNull(is, "IsoGridSquare.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        int vanilla = countArrayLengthInDiscard(rawClass);
        assertEquals(
                0,
                vanilla,
                "Sanity check: vanilla discard() bounds its lighting reset loop by the literal 4,"
                        + " so it should contain no ARRAYLENGTH. Got "
                        + vanilla
                        + " — the vanilla method shape changed and this test's marker is no longer"
                        + " valid.");

        byte[] transformed = new IsoGridSquareLosParallelPatch().transform(rawClass);
        assertNotNull(transformed);

        int patched = countArrayLengthInDiscard(transformed);
        assertTrue(
                patched > vanilla,
                "discard() should read lighting.length after patching (IsoGridSquareDiscardAdvice"
                        + " loops to the grown array's length). Found "
                        + patched
                        + " ARRAYLENGTH instructions; the advice was not woven in, so slots 4.."
                        + "MAX-1 keep stale bCouldSee bits across a square recycle.");
    }

    /** Counts {@code ARRAYLENGTH} instructions inside {@code discard()}. */
    private static int countArrayLengthInDiscard(byte[] classBytes) {
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
                                if (!"discard".equals(name) || !"()V".equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.ARRAYLENGTH) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return count[0];
    }
}
