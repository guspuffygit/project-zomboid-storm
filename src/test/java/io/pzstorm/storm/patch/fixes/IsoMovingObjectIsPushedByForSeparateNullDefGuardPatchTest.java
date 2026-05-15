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
 * Verifies that {@link IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch} injects the
 * null-{@code adef} guard advice into {@code
 * IsoMovingObject.isPushedByForSeparate(IsoMovingObject)} and only into that method.
 *
 * <p>Detection signal: the inlined advice reads {@code zombie.network.GameServer.server} (a {@code
 * GETSTATIC}) on entry. Vanilla {@code isPushedByForSeparate} contains no such read, so seeing one
 * after the transform proves the advice landed; seeing none in {@code isPushableForSeparate} proves
 * the matcher didn't leak.
 */
class IsoMovingObjectIsPushedByForSeparateNullDefGuardPatchTest implements UnitTest {

    private static final String ISO_MOVING_OBJECT = "zombie/iso/IsoMovingObject";
    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String SERVER_FIELD = "server";

    private static final String TARGET_METHOD = "isPushedByForSeparate";
    private static final String TARGET_DESC = "(Lzombie/iso/IsoMovingObject;)Z";

    private static final String SIBLING_METHOD = "isPushableForSeparate";
    private static final String SIBLING_DESC = "()Z";

    @Test
    void patchInjectsAdviceIntoIsPushedByForSeparateOnly() throws Exception {
        byte[] rawClass = readClassBytes(ISO_MOVING_OBJECT + ".class");
        byte[] transformed =
                new IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        int targetBefore = countServerReadsInMethod(rawClass, TARGET_METHOD, TARGET_DESC);
        int targetAfter = countServerReadsInMethod(transformed, TARGET_METHOD, TARGET_DESC);
        int siblingBefore = countServerReadsInMethod(rawClass, SIBLING_METHOD, SIBLING_DESC);
        int siblingAfter = countServerReadsInMethod(transformed, SIBLING_METHOD, SIBLING_DESC);

        assertEquals(
                0,
                targetBefore,
                "Vanilla isPushedByForSeparate should not read GameServer.server before patch");

        assertTrue(
                targetAfter >= 1,
                "Patched isPushedByForSeparate must contain >=1 GETSTATIC GameServer.server"
                        + " (advice not injected); got "
                        + targetAfter);

        // Scope check: matcher is named("isPushedByForSeparate").and(takesArguments(1)), so the
        // zero-arg sibling isPushableForSeparate must remain untouched.
        assertEquals(
                siblingBefore,
                siblingAfter,
                "Advice must not leak into IsoMovingObject."
                        + SIBLING_METHOD
                        + "; before="
                        + siblingBefore
                        + " after="
                        + siblingAfter);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countServerReadsInMethod(byte[] classBytes, String method, String desc) {
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
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && GAME_SERVER.equals(owner)
                                                && SERVER_FIELD.equals(fName)) {
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
