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
 * Verifies that {@link IsoObjectAdminSeeAllTargetAlphaPatch} weaves the {@code
 * StormAdminSeeAllAlphaGuard.shouldKeepVisible} gate into {@code IsoObject.setTargetAlpha(int,
 * float)} and nowhere else (the 1-arg overload delegates to it and must stay untouched).
 */
class IsoObjectAdminSeeAllTargetAlphaPatchTest implements UnitTest {

    private static final String ISO_OBJECT = "zombie/iso/IsoObject";
    private static final String HELPER_OWNER = "io/pzstorm/storm/client/StormAdminSeeAllAlphaGuard";
    private static final String HELPER_METHOD = "shouldKeepVisible";
    private static final String TARGET_METHOD = "setTargetAlpha";
    private static final String TARGET_DESC = "(IF)V";
    private static final String SIBLING_DESC = "(F)V";

    @Test
    void patchInjectsGateIntoTwoArgSetTargetAlphaOnly() throws Exception {
        byte[] rawClass = readClassBytes(ISO_OBJECT + ".class");
        byte[] transformed = new IsoObjectAdminSeeAllTargetAlphaPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(0, countHelperCalls(rawClass, TARGET_METHOD, TARGET_DESC));
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "advice not injected into setTargetAlpha(IF)V");
        assertEquals(
                0,
                countHelperCalls(transformed, TARGET_METHOD, SIBLING_DESC),
                "advice leaked into setTargetAlpha(F)V");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCalls(byte[] classBytes, String method, String desc) {
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
