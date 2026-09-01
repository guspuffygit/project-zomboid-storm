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
 * Verifies that {@link CombatManagerBallisticsNullGuardPatch} lands the null-controller skip in the
 * 3-arg {@code CombatManager.isHittableBallisticsTarget} and only there.
 *
 * <p>Detection signal: the inlined advice calls {@code BallisticsNullGuard.onNullController} via
 * INVOKESTATIC on its null branch. Vanilla contains no such call, so seeing it after the transform
 * proves the advice landed; seeing none in the 4-arg overload — which shares the name — proves the
 * {@code takesArguments(3)} matcher didn't leak onto the caller.
 */
class CombatManagerBallisticsNullGuardPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/CombatManager";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/advice/client/ballisticsnullguard/BallisticsNullGuard";
    private static final String HELPER_METHOD = "onNullController";

    private static final String METHOD = "isHittableBallisticsTarget";
    private static final String TARGET_DESC =
            "(Lzombie/core/physics/BallisticsController;FLzombie/iso/Vector3;)Z";
    private static final String SIBLING_DESC =
            "(Lzombie/characters/IsoGameCharacter;FFLzombie/iso/Vector3;)Z";

    @Test
    void patchInjectsNullSkipIntoControllerOverloadOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new CombatManagerBallisticsNullGuardPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCallsInMethod(rawClass, METHOD, TARGET_DESC),
                "Vanilla overload should not call " + HELPER_OWNER + "." + HELPER_METHOD);
        assertTrue(
                countHelperCallsInMethod(transformed, METHOD, TARGET_DESC) >= 1,
                "Patched overload must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD
                        + " (advice not injected)");

        assertEquals(
                0,
                countHelperCallsInMethod(transformed, METHOD, SIBLING_DESC),
                "Advice must not leak into the 4-arg IsoGameCharacter overload");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCallsInMethod(byte[] classBytes, String method, String desc) {
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
                                if (!method.equals(name) || !desc.equals(descriptor)) {
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
