package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.los.StormServerLosConfig;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the production AIOOBE seen with 12 LOS worker threads:
 *
 * <pre>
 *   Caused by: java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 4
 *       zombie.iso.IsoGridSquare.CalcVisibility(IsoGridSquare.java:9368)
 *       io.pzstorm.storm.los.StormServerLos.calcLOS(StormServerLos.java:351)
 * </pre>
 *
 * <p>Root cause: {@link IsoGridSquareLosParallelPatch} registers its {@code
 * LightingArraySizeWrapper} via {@code AsmVisitorWrapper.ForDeclaredMethods.method(isConstructor(),
 * wrapper)}. ByteBuddy expands {@code .method(matcher, ...)} to {@code
 * .invokable(isMethod().and(matcher), ...)} and {@code isMethod()} excludes constructors — so the
 * wrapper is matched against the empty set and never runs. The vanilla {@code lighting} array stays
 * length 4 and any worker slot &ge; 4 hits AIOOBE on {@code this.lighting[playerIndex]} (the first
 * line of {@code CalcVisibility}).
 *
 * <p>This test directly inspects the bytecode produced by the patch and asserts the {@code
 * ILighting} array allocation in every constructor uses size {@link StormServerLosConfig#MAX}, not
 * 4. With the buggy matcher it fails (size pushed is still {@code ICONST_4}). With {@code
 * .invokable(...)} or {@code .constructor(any(), ...)} it passes.
 */
class IsoGridSquareLosParallelPatchTest implements UnitTest {

    private static final String ISO_GRID_SQUARE = "zombie/iso/IsoGridSquare";
    private static final String ILIGHTING = "zombie/iso/IsoGridSquare$ILighting";

    @Test
    void patchGrowsLightingArrayToMaxInEveryConstructor() throws Exception {
        String resourcePath = ISO_GRID_SQUARE + ".class";
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "IsoGridSquare.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        List<Allocation> rawAllocations = findIlightingAllocations(rawClass);
        assertTrue(
                rawAllocations.size() >= 1,
                "Sanity check: vanilla IsoGridSquare should allocate the ILighting[] array at"
                        + " least once across its constructors. Found "
                        + rawAllocations.size()
                        + " allocations.");
        for (Allocation a : rawAllocations) {
            assertEquals(
                    4,
                    a.size,
                    "Sanity check: vanilla allocation in <init>"
                            + a.descriptor
                            + " should use size 4 (got "
                            + a.size
                            + ")");
        }

        byte[] transformed = new IsoGridSquareLosParallelPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        List<Allocation> patchedAllocations = findIlightingAllocations(transformed);
        assertEquals(
                rawAllocations.size(),
                patchedAllocations.size(),
                "Patch should not add or remove ILighting[] allocations — only resize them");

        int expectedSize = StormServerLosConfig.MAX;
        for (Allocation a : patchedAllocations) {
            assertEquals(
                    expectedSize,
                    a.size,
                    "ILighting[] allocation in <init>"
                            + a.descriptor
                            + " should be resized to StormServerLosConfig.MAX ("
                            + expectedSize
                            + "), got "
                            + a.size
                            + ". This is the production AIOOBE: slot >= 4 reads beyond"
                            + " lighting[].length and trips ArrayIndexOutOfBoundsException in"
                            + " IsoGridSquare.CalcVisibility's first line.");
        }
    }

    /**
     * Scans every constructor in {@code classBytes} for {@code ANEWARRAY ILighting} and records the
     * size constant pushed immediately before it. The vanilla bytecode pattern is {@code ICONST_4 /
     * ANEWARRAY ILighting}; the patched pattern is {@code (POP / pushInt(MAX)) / ANEWARRAY
     * ILighting} — leaving only the post-substitution push immediately before the ANEWARRAY when we
     * collapse runs.
     */
    private static List<Allocation> findIlightingAllocations(byte[] classBytes) {
        List<Allocation> out = new ArrayList<>();
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
                                if (!"<init>".equals(name)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    int lastPushedInt = Integer.MIN_VALUE;
                                    boolean lastPushedIntValid;

                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode >= Opcodes.ICONST_M1
                                                && opcode <= Opcodes.ICONST_5) {
                                            lastPushedInt = opcode - Opcodes.ICONST_0;
                                            lastPushedIntValid = true;
                                        } else if (opcode == Opcodes.POP) {
                                            // Substitution emits a POP before pushing the new size;
                                            // ignore — wait for the next push.
                                            lastPushedIntValid = false;
                                        } else {
                                            lastPushedIntValid = false;
                                        }
                                    }

                                    @Override
                                    public void visitIntInsn(int opcode, int operand) {
                                        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                                            lastPushedInt = operand;
                                            lastPushedIntValid = true;
                                        } else {
                                            lastPushedIntValid = false;
                                        }
                                    }

                                    @Override
                                    public void visitLdcInsn(Object value) {
                                        if (value instanceof Integer i) {
                                            lastPushedInt = i;
                                            lastPushedIntValid = true;
                                        } else {
                                            lastPushedIntValid = false;
                                        }
                                    }

                                    @Override
                                    public void visitTypeInsn(int opcode, String type) {
                                        if (opcode == Opcodes.ANEWARRAY && ILIGHTING.equals(type)) {
                                            assertTrue(
                                                    lastPushedIntValid,
                                                    "Could not statically determine the size"
                                                            + " constant pushed before ANEWARRAY "
                                                            + ILIGHTING
                                                            + " in <init>"
                                                            + descriptor);
                                            out.add(new Allocation(descriptor, lastPushedInt));
                                        }
                                        lastPushedIntValid = false;
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String name,
                                            String desc,
                                            boolean isInterface) {
                                        lastPushedIntValid = false;
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String name, String desc) {
                                        lastPushedIntValid = false;
                                    }

                                    @Override
                                    public void visitVarInsn(int opcode, int var) {
                                        lastPushedIntValid = false;
                                    }

                                    @Override
                                    public void visitJumpInsn(int opcode, Label label) {
                                        lastPushedIntValid = false;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return out;
    }

    private record Allocation(String descriptor, int size) {}
}
