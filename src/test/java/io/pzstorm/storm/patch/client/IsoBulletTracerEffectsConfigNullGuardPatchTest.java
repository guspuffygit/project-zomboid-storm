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
 * Verifies that {@link IsoBulletTracerEffectsConfigNullGuardPatch} lands the config guard in {@code
 * IsoBulletTracerEffects.createEffect(IsoGameCharacter)} and nowhere else.
 *
 * <p>Detection signal: the inlined advice calls {@code BulletTracerConfigGuard.onCreateEffect} via
 * INVOKESTATIC. Vanilla contains no such call, so seeing it after the transform proves the advice
 * landed; seeing none in the two {@code addEffect} overloads that call {@code createEffect} proves
 * the matcher did not leak.
 */
class IsoBulletTracerEffectsConfigNullGuardPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/iso/objects/IsoBulletTracerEffects";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/advice/client/tracerconfigguard/BulletTracerConfigGuard";
    private static final String HELPER_METHOD = "onCreateEffect";

    private static final String CREATE_EFFECT = "createEffect";
    private static final String ADD_EFFECT = "addEffect";

    @Test
    void patchInjectsConfigGuardIntoCreateEffectOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new IsoBulletTracerEffectsConfigNullGuardPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCalls(rawClass, CREATE_EFFECT),
                "Vanilla createEffect should not call " + HELPER_OWNER + "." + HELPER_METHOD);
        assertTrue(
                countHelperCalls(transformed, CREATE_EFFECT) >= 1,
                "Patched createEffect must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD
                        + " (advice not injected)");
        assertEquals(
                0,
                countHelperCalls(transformed, ADD_EFFECT),
                "Advice must not leak into the addEffect overloads");
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
                                if (!method.equals(name)) {
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
