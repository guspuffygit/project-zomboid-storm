package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.advice.gameserverstalledconnections.StalledConnectionReaper;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link PlayerDownloadServerChunkActivityPatch} inlines its activity stamp into
 * {@code PlayerDownloadServer.getClientChunkRequest}, and nowhere else, plus the reaper-side stamp
 * semantics the advice feeds.
 *
 * <p>Detection signal: the inlined advice calls {@code StalledConnectionReaper} via INVOKESTATIC —
 * same technique as {@link GameServerStalledConnectionReapPatchTest}.
 */
class PlayerDownloadServerChunkActivityPatchTest implements UnitTest {

    private static final String TARGET = "zombie/network/PlayerDownloadServer";
    private static final String REAPER_OWNER =
            "io/pzstorm/storm/advice/gameserverstalledconnections/StalledConnectionReaper";

    private static final String HOOK_HOST = "getClientChunkRequest";
    private static final String HOOK_HOST_DESC = "(Z)Lzombie/network/ClientChunkRequest;";

    // Unrelated PlayerDownloadServer methods used to assert no scope leak.
    private static final String SIBLING_UPDATE = "update";
    private static final String SIBLING_UPDATE_DESC = "()V";
    private static final String SIBLING_WAITING = "getWaitingRequests";
    private static final String SIBLING_WAITING_DESC = "()I";

    @Test
    void patchInjectsStampIntoGetClientChunkRequestOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET + ".class");
        byte[] transformed = new PlayerDownloadServerChunkActivityPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countReaperCalls(rawClass, HOOK_HOST, HOOK_HOST_DESC),
                "Vanilla getClientChunkRequest must not already call the reaper");
        assertTrue(
                countReaperCalls(transformed, HOOK_HOST, HOOK_HOST_DESC) >= 1,
                "Patched getClientChunkRequest must call StalledConnectionReaper");

        assertEquals(
                0,
                countReaperCalls(transformed, SIBLING_UPDATE, SIBLING_UPDATE_DESC),
                "Advice must not leak into PlayerDownloadServer." + SIBLING_UPDATE);
        assertEquals(
                0,
                countReaperCalls(transformed, SIBLING_WAITING, SIBLING_WAITING_DESC),
                "Advice must not leak into PlayerDownloadServer." + SIBLING_WAITING);
    }

    /** Fixed GUID base far outside anything another test could register. */
    private static final long TEST_GUID_BASE = 0x57AC711717L;

    @Test
    void chunkActivityStampCountsAsActiveOnlyWithinTheWindow() {
        long guid = TEST_GUID_BASE;
        long now = System.currentTimeMillis();

        assertFalse(
                StalledConnectionReaper.hasRecentChunkActivity(guid, now),
                "A connection that never requested chunks must not read as active");

        StalledConnectionReaper.recordChunkActivity(guid);
        assertTrue(
                StalledConnectionReaper.hasRecentChunkActivity(guid, System.currentTimeMillis()),
                "A fresh stamp must read as active");
        assertFalse(
                StalledConnectionReaper.hasRecentChunkActivity(
                        guid,
                        System.currentTimeMillis()
                                + StalledConnectionReaper.getChunkActivityWindowMs()
                                + 1),
                "A stamp older than the activity window must not read as active");
    }

    @Test
    void chunkActivityStampsAreKeyedByGuid() {
        StalledConnectionReaper.recordChunkActivity(TEST_GUID_BASE + 1);
        long now = System.currentTimeMillis();
        assertTrue(StalledConnectionReaper.hasRecentChunkActivity(TEST_GUID_BASE + 1, now));
        assertFalse(
                StalledConnectionReaper.hasRecentChunkActivity(TEST_GUID_BASE + 2, now),
                "A different GUID must not inherit the stamp");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countReaperCalls(byte[] classBytes, String method, String desc) {
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
                                                && REAPER_OWNER.equals(owner)) {
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
