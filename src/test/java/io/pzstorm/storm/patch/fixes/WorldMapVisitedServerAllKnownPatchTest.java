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
 * Bytecode-shape test for {@link WorldMapVisitedServerAllKnownPatch}: after {@code transform()},
 * {@code WorldMapVisitedServer.sendRequestData} must call {@code StormMapAllKnownSend.send}, and
 * the advice must not leak into {@code loadUser} or {@code update} — the whole point of the fix is
 * that the stored per-user data is never rewritten.
 */
class WorldMapVisitedServerAllKnownPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/worldMap/WorldMapVisitedServer";
    private static final String HELPER_OWNER = "io/pzstorm/storm/map/StormMapAllKnownSend";
    private static final String HELPER_METHOD = "send";

    private static final String TARGET_METHOD = "sendRequestData";

    @Test
    void patchInjectsAllKnownSenderIntoSendRequestData() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new WorldMapVisitedServerAllKnownPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(
                0,
                countHelperCalls(rawClass, TARGET_METHOD),
                "vanilla sendRequestData must not already call " + HELPER_OWNER);
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD) >= 1,
                "patched sendRequestData must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD);
        assertEquals(
                0,
                countHelperCalls(transformed, "loadUser"),
                "advice must not leak into WorldMapVisitedServer.loadUser");
        assertEquals(
                0,
                countHelperCalls(transformed, "update"),
                "advice must not leak into WorldMapVisitedServer.update");
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
