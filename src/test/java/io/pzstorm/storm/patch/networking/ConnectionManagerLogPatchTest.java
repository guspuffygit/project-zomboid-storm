package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.metrics.StormConnectionEventMetrics;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ConnectionManagerLogPatch} inlines a {@code StormConnectionEventMetrics}
 * counter bump into both {@code ConnectionManager.log} overloads, and into nothing else.
 *
 * <p>Both overloads matter independently: the {@code long} one carries RakNet-level events that
 * arrive before any {@code UdpConnection} exists, and the {@code IConnection} one carries every
 * per-packet handshake event. Missing either silently halves the funnel.
 *
 * <p>Uses ByteBuddy's bundled ASM because the standalone {@code org.ow2.asm:asm:9.1} test
 * dependency cannot read modern class files — same as {@link
 * GameServerStalledConnectionReapPatchTest}.
 */
class ConnectionManagerLogPatchTest implements UnitTest {

    private static final String CONNECTION_MANAGER = "zombie/network/ConnectionManager";
    private static final String METRICS_OWNER =
            "io/pzstorm/storm/metrics/StormConnectionEventMetrics";

    private static final String LOG = "log";
    private static final String LOG_GUID_DESC = "(Ljava/lang/String;Ljava/lang/String;J)V";
    private static final String LOG_CONNECTION_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Lzombie/network/IConnection;)V";

    // Unrelated ConnectionManager method used to assert no scope leak.
    private static final String SIBLING_METHOD = "process";
    private static final String SIBLING_DESC = "()V";

    @Test
    void patchInjectsCounterIntoBothLogOverloadsOnly() throws Exception {
        byte[] rawClass = readClassBytes(CONNECTION_MANAGER + ".class");
        byte[] transformed = new ConnectionManagerLogPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countMetricsCalls(rawClass, LOG, LOG_GUID_DESC),
                "Vanilla log(String,String,long) must not already call the metrics");
        assertEquals(
                0,
                countMetricsCalls(rawClass, LOG, LOG_CONNECTION_DESC),
                "Vanilla log(String,String,IConnection) must not already call the metrics");

        assertTrue(
                countMetricsCalls(transformed, LOG, LOG_GUID_DESC) >= 1,
                "Patched log(String,String,long) must record a connection event");
        assertTrue(
                countMetricsCalls(transformed, LOG, LOG_CONNECTION_DESC) >= 1,
                "Patched log(String,String,IConnection) must record a connection event");

        assertEquals(
                0,
                countMetricsCalls(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Advice must not leak into ConnectionManager." + SIBLING_METHOD);
    }

    @Test
    void unknownPairsAreBucketedRatherThanUnbounded() {
        // Guards the cardinality cap: 'connections' log call sites are bounded today, but a PZ
        // update adding a per-guid message string must not multiply the series count.
        for (int i = 0; i < 200; i++) {
            StormConnectionEventMetrics.record("flood-source-" + i, "flood-event-" + i);
        }
        assertTrue(
                StormConnectionEventMetrics.trackedPairs()
                        <= StormConnectionEventMetrics.MAX_TRACKED_PAIRS,
                "tracked label pairs must stay capped");
    }

    @Test
    void nullAndEmptyLabelsAreNormalized() {
        StormConnectionEventMetrics.record(null, null);
        StormConnectionEventMetrics.record("", "");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countMetricsCalls(byte[] classBytes, String method, String desc) {
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
                                                && METRICS_OWNER.equals(owner)) {
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
