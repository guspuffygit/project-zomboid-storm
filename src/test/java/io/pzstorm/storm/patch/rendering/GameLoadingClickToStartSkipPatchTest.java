package io.pzstorm.storm.patch.rendering;

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
 * Verifies that {@link GameLoadingClickToStartSkipPatch} injects the auto-click advice into {@code
 * GameLoadingState.update()} and only into that method. The patch is gated on {@code
 * -Dstorm.skipclickstart} at registration time, so {@code AllPatchesIntegrationTest} never sees it
 * — it needs this dedicated weave check.
 *
 * <p>Detection signal: the inlined advice reads its {@code ARMED_LOGGED} latch (a {@code GETSTATIC}
 * on the nested {@code UpdateAdvice} class). Vanilla {@code update} contains no such read; {@code
 * render} (which also touches the click-to-skip fields, so an easy over-match) must stay clean.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class GameLoadingClickToStartSkipPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/gameStates/GameLoadingState";
    private static final String ADVICE_CLASS =
            "io/pzstorm/storm/patch/rendering/GameLoadingClickToStartSkipPatch$UpdateAdvice";
    private static final String LATCH_FIELD = "ARMED_LOGGED";

    private static final String TARGET_METHOD = "update";
    private static final String TARGET_DESC = "()Lzombie/gameStates/GameStateMachine$StateAction;";

    // An unrelated method on the same class used to assert no scope leak.
    private static final String SIBLING_METHOD = "render";
    private static final String SIBLING_DESC = "()V";

    @Test
    void patchInjectsAdviceIntoUpdateOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new GameLoadingClickToStartSkipPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        int targetBefore = countLatchReadsInMethod(rawClass, TARGET_METHOD, TARGET_DESC);
        int targetAfter = countLatchReadsInMethod(transformed, TARGET_METHOD, TARGET_DESC);
        int siblingBefore = countLatchReadsInMethod(rawClass, SIBLING_METHOD, SIBLING_DESC);
        int siblingAfter = countLatchReadsInMethod(transformed, SIBLING_METHOD, SIBLING_DESC);

        // Vanilla update has no reference to the advice's log latch.
        assertEquals(
                0,
                targetBefore,
                "Vanilla update should not read the advice ARMED_LOGGED latch before patch");

        // After transform the advice's once-per-session log latch must be inlined, producing at
        // least one GETSTATIC <Advice>.ARMED_LOGGED in the method body.
        assertTrue(
                targetAfter >= 1,
                "Patched update must contain >=1 GETSTATIC "
                        + ADVICE_CLASS
                        + ".ARMED_LOGGED (advice not injected); got "
                        + targetAfter);

        // Scope check: the matcher is named("update").and(takesArguments(0)), so render must
        // remain untouched.
        assertEquals(
                siblingBefore,
                siblingAfter,
                "Advice must not leak into GameLoadingState."
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

    private static int countLatchReadsInMethod(byte[] classBytes, String method, String desc) {
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
                                                && ADVICE_CLASS.equals(owner)
                                                && LATCH_FIELD.equals(fName)) {
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
