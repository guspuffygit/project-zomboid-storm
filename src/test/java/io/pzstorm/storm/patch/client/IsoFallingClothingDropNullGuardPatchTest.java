package io.pzstorm.storm.patch.client;

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
 * Verifies that {@link IsoFallingClothingDropNullGuardPatch} lands the null-square guard in {@code
 * IsoFallingClothing.drop()} and nowhere else.
 *
 * <p>Detection signal: the inlined advice calls {@code FallingClothingDropGuard.onNullSquare} via
 * INVOKESTATIC on its null branch. Vanilla contains no such call, so seeing it after the transform
 * proves the advice landed; seeing none in {@code update()} or {@code collideWall()} — the callers
 * of {@code drop()} — proves the matcher did not leak.
 */
class IsoFallingClothingDropNullGuardPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/iso/objects/IsoFallingClothing";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/advice/client/fallingclothingguard/FallingClothingDropGuard";
    private static final String HELPER_METHOD = "onNullSquare";
    private static final String VOID_NO_ARGS = "()V";

    @Test
    void patchInjectsNullSkipIntoDropOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new IsoFallingClothingDropNullGuardPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCalls(rawClass, "drop"),
                "Vanilla drop() should not call " + HELPER_OWNER + "." + HELPER_METHOD);
        assertTrue(
                countHelperCalls(transformed, "drop") >= 1,
                "Patched drop() must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD
                        + " (advice not injected)");
        assertEquals(
                0, countHelperCalls(transformed, "update"), "Advice must not leak into update()");
        assertEquals(
                0,
                countHelperCalls(transformed, "collideWall"),
                "Advice must not leak into collideWall()");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCalls(byte[] classBytes, String method) {
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
                                if (!method.equals(name) || !VOID_NO_ARGS.equals(descriptor)) {
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
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && HELPER_OWNER.equals(owner)
                                                && HELPER_METHOD.equals(mName)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return hits[0];
    }
}
