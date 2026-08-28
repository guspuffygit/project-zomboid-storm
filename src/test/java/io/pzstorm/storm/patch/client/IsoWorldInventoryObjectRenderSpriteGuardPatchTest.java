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
 * Verifies that {@link IsoWorldInventoryObjectRenderSpriteGuardPatch} injects the {@code
 * WorldItemSpriteGuard.restoreSprite} repair into the 7-arg {@code IsoWorldInventoryObject.render}
 * and only there.
 *
 * <p>Detection signal: the inlined advice calls the helper via INVOKESTATIC. Vanilla contains no
 * such call, so seeing it after the transform proves the advice landed; seeing none in {@code
 * renderObjectPicker} — which reads the same field behind its own null check — proves the {@code
 * named("render")} matcher didn't leak onto a sibling.
 */
class IsoWorldInventoryObjectRenderSpriteGuardPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/iso/objects/IsoWorldInventoryObject";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/advice/client/worlditemspriteguard/WorldItemSpriteGuard";
    private static final String HELPER_METHOD = "restoreSprite";

    private static final String TARGET_METHOD = "render";
    private static final String TARGET_DESC =
            "(FFFLzombie/core/textures/ColorInfo;ZZLzombie/core/opengl/Shader;)V";

    private static final String SIBLING_METHOD = "renderObjectPicker";
    private static final String SIBLING_DESC = "(FFFLzombie/core/textures/ColorInfo;)V";

    @Test
    void patchInjectsSpriteRepairIntoRenderOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed =
                new IsoWorldInventoryObjectRenderSpriteGuardPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCallsInMethod(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla render should not call " + HELPER_OWNER + "." + HELPER_METHOD);
        assertTrue(
                countHelperCallsInMethod(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched render must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD
                        + " (advice not injected)");

        assertEquals(
                countHelperCallsInMethod(rawClass, SIBLING_METHOD, SIBLING_DESC),
                countHelperCallsInMethod(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Advice must not leak into renderObjectPicker");
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
