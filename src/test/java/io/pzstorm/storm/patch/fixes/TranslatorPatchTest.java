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
 * Verifies that {@link TranslatorPatch} injects {@code ZeroArgFormatAdvice} into {@code
 * Translator.reportMissingArgumentsFromPastAbuse}, so zero-arg {@code getText} calls on texts with
 * positional specifiers return the raw text without throwing and warn-logging every frame.
 *
 * <p>Detection signal: the advice is inlined, so the patched method gains an {@code LDC} constant
 * ({@code "%1$s"}) that the vanilla method does not contain. Asserting it appears in the target
 * method and not in {@code getText} proves both that the advice landed and that the matcher didn't
 * leak.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class TranslatorPatchTest implements UnitTest {

    private static final String TRANSLATOR = "zombie/core/Translator";

    /**
     * A constant only the inlined {@code ZeroArgFormatAdvice} loads; vanilla {@code
     * reportMissingArgumentsFromPastAbuse} references {@code "%$1\$s"} and {@code "%%(\d+)"} but
     * never the plain literal {@code "%1$s"}.
     */
    private static final String ZERO_ARG_ADVICE_CONSTANT = "%1$s";

    private static final String REPORT_METHOD = "reportMissingArgumentsFromPastAbuse";
    private static final String REPORT_DESC =
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";

    // getText delegates to the report method and must not be instrumented itself.
    private static final String SIBLING_METHOD = "getText";
    private static final String SIBLING_DESC =
            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";

    @Test
    void patchInjectsZeroArgFormatAdviceIntoReportMethodOnly() throws Exception {
        byte[] rawClass = readClassBytes(TRANSLATOR + ".class");
        byte[] transformed = new TranslatorPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countConstants(rawClass, REPORT_METHOD, REPORT_DESC, ZERO_ARG_ADVICE_CONSTANT),
                "Vanilla " + REPORT_METHOD + " must not contain the advice's marker constant");

        assertTrue(
                countConstants(transformed, REPORT_METHOD, REPORT_DESC, ZERO_ARG_ADVICE_CONSTANT)
                        >= 1,
                "Patched "
                        + REPORT_METHOD
                        + " must inline ZeroArgFormatAdvice (advice not injected)");

        assertEquals(
                countConstants(rawClass, SIBLING_METHOD, SIBLING_DESC, ZERO_ARG_ADVICE_CONSTANT),
                countConstants(transformed, SIBLING_METHOD, SIBLING_DESC, ZERO_ARG_ADVICE_CONSTANT),
                "ZeroArgFormatAdvice must not leak into Translator." + SIBLING_METHOD);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countConstants(
            byte[] classBytes, String method, String desc, String constant) {
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
                                        if (constant.equals(value)) {
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
