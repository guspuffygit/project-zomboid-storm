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
 * Verifies that {@link TranslatorPatch} injects {@code GetTextAdvice} into {@code
 * Translator.getText(String, Object...)}, so a string that matches no known translation key prefix
 * is returned as-is instead of triggering a spurious "Missing translation" error log.
 *
 * <p>Detection signal: the advice is inlined, so the patched method gains {@code LDC} constants for
 * the known key prefixes (e.g. {@code "SurvivorSurname_"}) that vanilla {@code getText} — a
 * one-line delegation — does not contain. Asserting they appear in the target method and not in
 * {@code getTextOrNull} proves both that the advice landed and that the matcher didn't leak.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class TranslatorPatchTest implements UnitTest {

    private static final String TRANSLATOR = "zombie/core/Translator";

    /** A prefix constant only the inlined advice loads; see {@code GetTextAdvice.onEnter}. */
    private static final String ADVICE_PREFIX_CONSTANT = "SurvivorSurname_";

    private static final String TARGET_METHOD = "getText";
    private static final String TARGET_DESC =
            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";

    // getTextOrNull has the same shape as getText but must not be instrumented itself.
    private static final String SIBLING_METHOD = "getTextOrNull";
    private static final String SIBLING_DESC =
            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";

    @Test
    void patchInjectsGetTextAdviceIntoGetTextOnly() throws Exception {
        byte[] rawClass = readClassBytes(TRANSLATOR + ".class");
        byte[] transformed = new TranslatorPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countAdviceConstants(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla " + TARGET_METHOD + " must not contain the advice's prefix constant");

        assertTrue(
                countAdviceConstants(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched " + TARGET_METHOD + " must inline GetTextAdvice (advice not injected)");

        assertEquals(
                countAdviceConstants(rawClass, SIBLING_METHOD, SIBLING_DESC),
                countAdviceConstants(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Advice must not leak into Translator." + SIBLING_METHOD);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countAdviceConstants(byte[] classBytes, String method, String desc) {
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
                                    public void visitLdcInsn(Object value) {
                                        if (ADVICE_PREFIX_CONSTANT.equals(value)) {
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
