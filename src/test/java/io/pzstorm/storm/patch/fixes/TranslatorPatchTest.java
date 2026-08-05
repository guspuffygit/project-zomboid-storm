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
 * Verifies that {@link TranslatorPatch} injects {@code FormatFailureAdvice} into {@code
 * Translator.reportMissingArgumentsFromPastAbuse}, so a translation string containing an unescaped
 * {@code %} returns unformatted text instead of throwing into the calling Lua chunk.
 *
 * <p>Detection signal: the inlined advice calls {@code FormatFailureAdvice.report(String)} — an
 * {@code INVOKESTATIC} that vanilla does not contain. Asserting it appears in the target method and
 * nowhere else proves both that the advice landed and that the matcher didn't leak.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class TranslatorPatchTest implements UnitTest {

    private static final String TRANSLATOR = "zombie/core/Translator";

    private static final String ADVICE =
            "io/pzstorm/storm/patch/fixes/TranslatorPatch$FormatFailureAdvice";
    private static final String REPORT = "report";

    private static final String TARGET_METHOD = "reportMissingArgumentsFromPastAbuse";
    private static final String TARGET_DESC =
            "(Ljava/lang/String;[Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;";

    // getTextOrNull routes through the target method but must not be instrumented itself.
    private static final String SIBLING_METHOD = "getTextOrNull";
    private static final String SIBLING_DESC =
            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";

    @Test
    void patchInjectsFormatFailureAdviceIntoReportMethodOnly() throws Exception {
        byte[] rawClass = readClassBytes(TRANSLATOR + ".class");
        byte[] transformed = new TranslatorPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countReportCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla " + TARGET_METHOD + " must not call the advice");

        assertTrue(
                countReportCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched "
                        + TARGET_METHOD
                        + " must call FormatFailureAdvice.report (advice not injected)");

        assertEquals(
                countReportCalls(rawClass, SIBLING_METHOD, SIBLING_DESC),
                countReportCalls(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Advice must not leak into Translator." + SIBLING_METHOD);
    }

    /**
     * The advice only rescues a throwing format call if the transform actually installs an
     * exception handler around the method body.
     */
    @Test
    void patchAddsExceptionHandlerToReportMethod() throws Exception {
        byte[] rawClass = readClassBytes(TRANSLATOR + ".class");
        byte[] transformed = new TranslatorPatch().transform(rawClass);

        int before = countHandlers(rawClass);
        int after = countHandlers(transformed);
        assertTrue(
                after > before,
                "Patched "
                        + TARGET_METHOD
                        + " must gain a try/catch for the throwing format call; before="
                        + before
                        + " after="
                        + after);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countReportCalls(byte[] classBytes, String method, String desc) {
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
                                                && ADVICE.equals(owner)
                                                && REPORT.equals(mName)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return hits[0];
    }

    private static int countHandlers(byte[] classBytes) {
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
                                if (!TARGET_METHOD.equals(name)
                                        || !TARGET_DESC.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitTryCatchBlock(
                                            net.bytebuddy.jar.asm.Label start,
                                            net.bytebuddy.jar.asm.Label end,
                                            net.bytebuddy.jar.asm.Label handler,
                                            String type) {
                                        hits[0]++;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return hits[0];
    }
}
