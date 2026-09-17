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
 * Verifies that {@link IsoGridSquareRemoveGlassAttachmentsPatch} routes {@code
 * IsoGridSquare.removeGlassAttachments(IsoWindow)} through {@link GlassAttachmentRemovalGuard} and
 * nothing else, and that the guard's index arithmetic never re-examines a slot unless the list
 * shrank.
 */
class IsoGridSquareRemoveGlassAttachmentsPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/iso/IsoGridSquare";
    private static final String HELPER_CLASS =
            "io/pzstorm/storm/patch/fixes/GlassAttachmentRemovalGuard";
    private static final String TARGET_METHOD = "removeGlassAttachments";

    // Sibling that also removes tile objects; an easy over-match.
    private static final String SIBLING_METHOD = "RemoveTileObject";

    @Test
    void patchInjectsHelperIntoRemoveGlassAttachmentsOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new IsoGridSquareRemoveGlassAttachmentsPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCalls(rawClass, TARGET_METHOD),
                "Vanilla removeGlassAttachments must not reference the Storm helper");
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD) >= 1,
                "Patched removeGlassAttachments must call " + HELPER_CLASS);
        assertEquals(
                0,
                countHelperCalls(transformed, SIBLING_METHOD),
                "Advice must not leak into " + SIBLING_METHOD);
    }

    @Test
    void indexStepsBackOnlyWhenTheListShrank() {
        // Removal succeeded: the slot now holds the next object, examine it again.
        assertEquals(3, GlassAttachmentRemovalGuard.nextIndex(3, 10, 9));
        // Multi-tile removal took two parts off this square: still re-examine the slot.
        assertEquals(3, GlassAttachmentRemovalGuard.nextIndex(3, 10, 8));
        // Removal silently failed (the vanilla infinite-loop case): move on.
        assertEquals(4, GlassAttachmentRemovalGuard.nextIndex(3, 10, 10));
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
                                        if (HELPER_CLASS.equals(owner)) {
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
