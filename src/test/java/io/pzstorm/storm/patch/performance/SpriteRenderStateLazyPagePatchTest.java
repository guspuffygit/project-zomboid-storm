package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SpriteRenderStateLazyPagePatch} puts a {@code LazyTilePages.request} call
 * into the textured {@code render} and {@code renderPoly} overloads, which drop unready textures,
 * and nowhere else.
 */
class SpriteRenderStateLazyPagePatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/core/sprite/GenericSpriteRenderState";
    private static final String HELPER_OWNER = "io/pzstorm/storm/advice/lazytiles/LazyTilePages";
    private static final String HELPER_METHOD = "request";
    private static final String TEX = "Lzombie/core/textures/Texture;";

    @Test
    void patchRequestsPagesFromTexturedRenderOverloadsOnly() throws Exception {
        byte[] raw = readClassBytes(TARGET_CLASS + ".class");
        byte[] patched = new SpriteRenderStateLazyPagePatch().transform(raw);
        assertNotNull(patched);

        String uiRender = "(" + TEX + "FFFFFFFFLjava/util/function/Consumer;)V";
        String polyRender = "(" + TEX + "FFFFFFFFFFFF)V";

        assertEquals(0, helperCalls(raw, "render", uiRender));
        assertEquals(1, helperCalls(patched, "render", uiRender));
        assertEquals(1, helperCalls(patched, "renderPoly", polyRender));
        assertEquals(0, helperCalls(patched, "renderPoly", "(FFFFFFFFFFFF)V"));
        assertEquals(0, helperCalls(patched, "renderline", "(" + TEX + "FFFFFFFFF)V"));
        assertEquals(0, helperCalls(patched, "renderRect", "(IIIIFFFF)V"));
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int helperCalls(byte[] classBytes, String method, String desc) {
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
