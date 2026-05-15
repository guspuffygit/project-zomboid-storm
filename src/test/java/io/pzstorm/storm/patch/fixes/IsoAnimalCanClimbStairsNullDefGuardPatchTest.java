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
 * Verifies that {@link IsoAnimalCanClimbStairsNullDefGuardPatch} injects the null-{@code adef}
 * guard advice into {@code IsoAnimal.canClimbStairs()} and only into that method.
 *
 * <p>Detection signal: the inlined advice reads {@code zombie.network.GameServer.server} (a {@code
 * GETSTATIC}) on entry. Vanilla {@code canClimbStairs} contains no such read, so seeing one after
 * the transform proves the advice landed on the right method; seeing none in an unrelated method
 * proves the matcher didn't leak.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class IsoAnimalCanClimbStairsNullDefGuardPatchTest implements UnitTest {

    private static final String ISO_ANIMAL = "zombie/characters/animals/IsoAnimal";
    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String SERVER_FIELD = "server";

    private static final String TARGET_METHOD = "canClimbStairs";
    private static final String TARGET_DESC = "()Z";

    // A simple unrelated zero-arg method on IsoAnimal used to assert no scope leak.
    private static final String SIBLING_METHOD = "getAnimalType";
    private static final String SIBLING_DESC = "()Ljava/lang/String;";

    @Test
    void patchInjectsAdviceIntoCanClimbStairsOnly() throws Exception {
        byte[] rawClass = readClassBytes(ISO_ANIMAL + ".class");
        byte[] transformed = new IsoAnimalCanClimbStairsNullDefGuardPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        int targetBefore = countServerReadsInMethod(rawClass, TARGET_METHOD, TARGET_DESC);
        int targetAfter = countServerReadsInMethod(transformed, TARGET_METHOD, TARGET_DESC);
        int siblingBefore = countServerReadsInMethod(rawClass, SIBLING_METHOD, SIBLING_DESC);
        int siblingAfter = countServerReadsInMethod(transformed, SIBLING_METHOD, SIBLING_DESC);

        // Vanilla canClimbStairs has no GameServer.server read.
        assertEquals(
                0,
                targetBefore,
                "Vanilla canClimbStairs should not read GameServer.server before patch");

        // After transform the advice's `if (!GameServer.server) return false` must be inlined,
        // producing at least one GETSTATIC GameServer.server in the method body.
        assertTrue(
                targetAfter >= 1,
                "Patched canClimbStairs must contain >=1 GETSTATIC GameServer.server"
                        + " (advice not injected); got "
                        + targetAfter);

        // Scope check: the matcher is named("canClimbStairs").and(takesArguments(0)), so an
        // unrelated zero-arg method like getAnimalType must remain untouched.
        assertEquals(
                siblingBefore,
                siblingAfter,
                "Advice must not leak into IsoAnimal."
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
