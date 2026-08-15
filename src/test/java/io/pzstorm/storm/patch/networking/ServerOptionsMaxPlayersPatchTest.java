package io.pzstorm.storm.patch.networking;

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
 * Verifies that {@link ServerOptionsMaxPlayersPatch} inlines the {@code
 * StormMaxPlayersConfig.overrideOrVanilla} call into {@code ServerOptions.getMaxPlayers()}, and
 * nowhere else.
 *
 * <p>Detection signal: an INVOKESTATIC to the config class. Vanilla {@code ServerOptions} contains
 * none, so its presence after the transform proves the advice landed; its absence from a sibling
 * method proves the {@code named("getMaxPlayers")} scope did not leak.
 *
 * <p>Uses ByteBuddy's bundled ASM for the same reason as {@link GameServerConnectionCapPatchTest} —
 * the standalone {@code org.ow2.asm:asm:9.1} test dependency cannot read modern class files.
 */
class ServerOptionsMaxPlayersPatchTest implements UnitTest {

    private static final String SERVER_OPTIONS = "zombie/network/ServerOptions";
    private static final String CONFIG_OWNER = "io/pzstorm/storm/connection/StormMaxPlayersConfig";

    private static final String GET_MAX_PLAYERS = "getMaxPlayers";
    private static final String GET_MAX_PLAYERS_DESC = "()I";

    // Unrelated ServerOptions method used to assert no scope leak.
    private static final String SIBLING_METHOD = "getNumOptions";
    private static final String SIBLING_DESC = "()I";

    @Test
    void patchRoutesGetMaxPlayersThroughTheConfigOnly() throws Exception {
        byte[] rawClass = readClassBytes(SERVER_OPTIONS + ".class");
        byte[] transformed = new ServerOptionsMaxPlayersPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countConfigCalls(rawClass, GET_MAX_PLAYERS, GET_MAX_PLAYERS_DESC),
                "Vanilla getMaxPlayers must not already call the config");
        assertEquals(
                1,
                countConfigCalls(transformed, GET_MAX_PLAYERS, GET_MAX_PLAYERS_DESC),
                "Patched getMaxPlayers must route its return value through"
                        + " StormMaxPlayersConfig.overrideOrVanilla");
        assertEquals(
                0,
                countConfigCalls(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Advice must not leak into ServerOptions." + SIBLING_METHOD);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countConfigCalls(byte[] classBytes, String method, String desc) {
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
                                                && CONFIG_OWNER.equals(owner)
                                                && "overrideOrVanilla".equals(mName)) {
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
