package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.advice.requestdatafix.RequestDataAckAdvice;
import io.pzstorm.storm.advice.requestdatafix.RequestDataDisconnectAdvice;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import zombie.network.RequestDataManager;
import zombie.network.packets.RequestDataPacket;

/**
 * Weave coverage for {@link RequestDataManagerFixPatch} plus behavioral coverage of the advice
 * helpers against real (reflectively constructed) {@code RequestDataManager.RequestData} entries —
 * exercising exactly the states vanilla {@code ACKWasReceived} crashed on and the cross-connection
 * purge vanilla {@code disconnect} performed.
 */
class RequestDataManagerFixPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/network/RequestDataManager";
    private static final String ACK_ADVICE_OWNER =
            "io/pzstorm/storm/advice/requestdatafix/RequestDataAckAdvice";
    private static final String DISCONNECT_ADVICE_OWNER =
            "io/pzstorm/storm/advice/requestdatafix/RequestDataDisconnectAdvice";

    // ---------------------------------------------------------------- weave

    @Test
    void patchInjectsBothAdvicesIntoTheirMethodsOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new RequestDataManagerFixPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countCallsToOwner(rawClass, "ACKWasReceived", ACK_ADVICE_OWNER),
                "Vanilla ACKWasReceived must not reference the advice class");
        assertTrue(
                countCallsToOwner(transformed, "ACKWasReceived", ACK_ADVICE_OWNER) >= 1,
                "Patched ACKWasReceived must call RequestDataAckAdvice.run (advice not injected)");
        assertTrue(
                countCallsToOwner(transformed, "disconnect", DISCONNECT_ADVICE_OWNER) >= 1,
                "Patched disconnect must call RequestDataDisconnectAdvice.run"
                        + " (advice not injected)");

        // the client half of the class must stay vanilla
        for (String sibling : new String[] {"receiveClientData", "putDataForTransmit", "clear"}) {
            assertEquals(
                    0,
                    countCallsToOwner(transformed, sibling, ACK_ADVICE_OWNER)
                            + countCallsToOwner(transformed, sibling, DISCONNECT_ADVICE_OWNER),
                    "Advice must not leak into RequestDataManager." + sibling);
        }
    }

    // ------------------------------------------------- ACK entry selection

    @Test
    void findRequestOnEmptyListReturnsNullInsteadOfThrowing() {
        // vanilla: requests.get(0) on an empty list -> IndexOutOfBoundsException
        Object found =
                assertDoesNotThrow(
                        () ->
                                RequestDataAckAdvice.findRequest(
                                        new ArrayList<>(),
                                        42L,
                                        RequestDataPacket.RequestID.WorldMap));
        assertNull(found);
    }

    @Test
    void findRequestWithNoMatchingConnectionReturnsNullInsteadOfThrowing() throws Exception {
        // vanilla: the loop runs to i == size() and throws on the final get(i)
        List<Object> requests = new ArrayList<>();
        requests.add(newEntry(RequestDataPacket.RequestID.WorldMap, 1L));
        requests.add(newEntry(RequestDataPacket.RequestID.RadioData, 2L));

        Object found =
                assertDoesNotThrow(
                        () ->
                                RequestDataAckAdvice.findRequest(
                                        requests, 999L, RequestDataPacket.RequestID.WorldMap));
        assertNull(found);
    }

    @Test
    void findRequestMatchesOnBothConnectionAndId() throws Exception {
        Object worldMap = newEntry(RequestDataPacket.RequestID.WorldMap, 7L);
        Object radio = newEntry(RequestDataPacket.RequestID.RadioData, 7L);
        List<Object> requests = new ArrayList<>(List.of(radio, worldMap));

        // vanilla matched the first entry by connection alone, then silently dropped the
        // ACK when its id differed; the fix must keep scanning to the right entry
        assertSame(
                worldMap,
                RequestDataAckAdvice.findRequest(
                        requests, 7L, RequestDataPacket.RequestID.WorldMap));
        assertSame(
                radio,
                RequestDataAckAdvice.findRequest(
                        requests, 7L, RequestDataPacket.RequestID.RadioData));
        assertNull(
                RequestDataAckAdvice.findRequest(
                        requests, 7L, RequestDataPacket.RequestID.PlayerVisited));
    }

    // ---------------------------------------------------- disconnect purge

    @Test
    void purgeRemovesOnlyTheDisconnectingConnectionsEntries() throws Exception {
        long now = System.currentTimeMillis();
        Object leaver = newEntry(RequestDataPacket.RequestID.WorldMap, 1L);
        // a slow joiner mid-transfer, 90s since its last burst: vanilla's global 60s
        // clause reaped this entry on a stranger's disconnect
        Object slowJoiner = newEntry(RequestDataPacket.RequestID.WorldMap, 2L);
        setCreationTime(slowJoiner, now - 90_000L);
        List<Object> requests = new ArrayList<>(List.of(leaver, slowJoiner));

        int stale = RequestDataDisconnectAdvice.purge(requests, 1L, now);

        assertEquals(0, stale);
        assertEquals(List.of(slowJoiner), requests, "The slow joiner's transfer must survive");
    }

    @Test
    void purgeStillReapsGenuinelyLeakedEntries() throws Exception {
        long now = System.currentTimeMillis();
        Object leaked = newEntry(RequestDataPacket.RequestID.RadioData, 3L);
        setCreationTime(leaked, now - RequestDataDisconnectAdvice.STALE_AGE_MS - 1L);
        Object live = newEntry(RequestDataPacket.RequestID.WorldMap, 4L);
        List<Object> requests = new ArrayList<>(List.of(leaked, live));

        int stale = RequestDataDisconnectAdvice.purge(requests, 1L, now);

        assertEquals(1, stale);
        assertEquals(List.of(live), requests);
    }

    // -------------------------------------------------------------- helpers

    /** Builds a real {@code RequestDataManager.RequestData} via its (id, bufferSize, guid) ctor. */
    private static Object newEntry(RequestDataPacket.RequestID id, long connectionGuid)
            throws Exception {
        Class<?> entryClass = Class.forName("zombie.network.RequestDataManager$RequestData");
        Constructor<?> ctor =
                entryClass.getDeclaredConstructor(
                        RequestDataPacket.RequestID.class, int.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id, 16, connectionGuid);
    }

    private static void setCreationTime(Object entry, long creationTime) throws Exception {
        Field field = entry.getClass().getDeclaredField("creationTime");
        field.setAccessible(true);
        field.setLong(entry, creationTime);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countCallsToOwner(byte[] classBytes, String methodName, String owner) {
        int[] count = {0};
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
                                if (!name.equals(methodName)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String insnOwner,
                                            String insnName,
                                            String insnDescriptor,
                                            boolean isInterface) {
                                        if (owner.equals(insnOwner)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    // keeps the RequestDataManager import live so a game rename breaks this test at compile time
    @SuppressWarnings("unused")
    private static final Class<?> TARGET = RequestDataManager.class;
}
