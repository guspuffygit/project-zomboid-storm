package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class SteamUgcTest {

    @Test
    void joinReadyIsExactlySubscribedInstalledWithNothingPending() {
        assertTrue(SteamUgc.isJoinReady(SteamUgc.STATE_SUBSCRIBED | SteamUgc.STATE_INSTALLED));

        assertFalse(SteamUgc.isJoinReady(0));
        assertFalse(SteamUgc.isJoinReady(SteamUgc.STATE_INSTALLED), "unsubscribed prompts in-game");
        assertFalse(SteamUgc.isJoinReady(SteamUgc.STATE_SUBSCRIBED));
        assertFalse(
                SteamUgc.isJoinReady(
                        SteamUgc.STATE_SUBSCRIBED
                                | SteamUgc.STATE_INSTALLED
                                | SteamUgc.STATE_NEEDS_UPDATE));
        assertFalse(
                SteamUgc.isJoinReady(
                        SteamUgc.STATE_SUBSCRIBED
                                | SteamUgc.STATE_INSTALLED
                                | SteamUgc.STATE_DOWNLOAD_PENDING));
    }

    /**
     * DownloadItemResult_t is {AppId_t appId; PublishedFileId_t fileId; EResult result} — Valve
     * packs callback structs at 4 bytes on linux/macOS (fileId at 4, result at 12) and 8 on Windows
     * (fileId at 8, result at 16).
     */
    @Test
    void downloadResultOffsetsFollowValveCallbackPacking() {
        long fileId = 3676481910L;

        ByteBuffer packSmall = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        packSmall.putInt(0, 108600);
        packSmall.putLong(4, fileId);
        packSmall.putInt(12, SteamUgc.RESULT_OK);
        MemorySegment small = MemorySegment.ofBuffer(packSmall);
        assertEquals(fileId, SteamUgc.downloadResultItemId(small, true));
        assertEquals(SteamUgc.RESULT_OK, SteamUgc.downloadResultCode(small, true));

        ByteBuffer packWide = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        packWide.putInt(0, 108600);
        packWide.putLong(8, fileId);
        packWide.putInt(16, 2); // k_EResultFail
        MemorySegment wide = MemorySegment.ofBuffer(packWide);
        assertEquals(fileId, SteamUgc.downloadResultItemId(wide, false));
        assertEquals(2, SteamUgc.downloadResultCode(wide, false));
    }
}
