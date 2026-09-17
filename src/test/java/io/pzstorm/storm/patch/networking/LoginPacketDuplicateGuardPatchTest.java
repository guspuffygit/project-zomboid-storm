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
 * The guard must land on {@code LoginPacket.processServer} only. Landing on {@code parse} would
 * skip the read and leave the packet buffer mispositioned for every vanilla client.
 */
class LoginPacketDuplicateGuardPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/network/packets/connection/LoginPacket";
    private static final String HELPER_OWNER = "io/pzstorm/storm/connection/StormTcpLogin";
    private static final String HELPER = "isDuplicateLogin";

    private static final String PROCESS_METHOD = "processServer";
    private static final String PROCESS_DESC =
            "(Lzombie/network/PacketTypes$PacketType;Lzombie/core/raknet/UdpConnection;)V";
    private static final String PARSE_METHOD = "parse";
    private static final String PARSE_DESC =
            "(Lzombie/core/network/ByteBufferReader;Lzombie/core/network/IConnection;)V";

    @Test
    void guardLandsOnProcessServerOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new LoginPacketDuplicateGuardPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(0, countCalls(rawClass, PROCESS_METHOD, PROCESS_DESC));
        assertTrue(countCalls(transformed, PROCESS_METHOD, PROCESS_DESC) >= 1);
        assertEquals(0, countCalls(transformed, PARSE_METHOD, PARSE_DESC));
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countCalls(byte[] classBytes, String method, String desc) {
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
                                                && HELPER.equals(mName)) {
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
