package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class A2sInfoTest {

    private static byte[] infoReply(String name, int players, int maxPlayers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {-1, -1, -1, -1, 0x49, 0x11});
        for (String s : new String[] {name, "Muldraugh, KY", "zomboid", "Project Zomboid"}) {
            out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        }
        // 2-byte app id (PZ's real id overflows it; the value is skipped either way)
        out.write(0x38);
        out.write(0xA8);
        out.write(players);
        out.write(maxPlayers);
        // trailing fields (bots, server type, ...) this parser never reads
        out.writeBytes(new byte[] {0, 'd', 'l', 0, 1});
        return out.toByteArray();
    }

    @Test
    void parsesInfoReply() {
        byte[] reply = infoReply("After The Fall PvPvE", 87, 120);
        A2sInfo.Result result = A2sInfo.parse(reply, reply.length);
        assertEquals("After The Fall PvPvE", result.serverName);
        assertEquals(87, result.players);
        assertEquals(120, result.maxPlayers);
    }

    @Test
    void playerCountsAreUnsigned() {
        byte[] reply = infoReply("Big", 200, 255);
        A2sInfo.Result result = A2sInfo.parse(reply, reply.length);
        assertEquals(200, result.players);
        assertEquals(255, result.maxPlayers);
    }

    @Test
    void rejectsChallengeAndGarbage() {
        byte[] challenge = {-1, -1, -1, -1, 0x41, 1, 2, 3, 4};
        assertNull(A2sInfo.parse(challenge, challenge.length));
        byte[] garbage = {1, 2, 3};
        assertNull(A2sInfo.parse(garbage, garbage.length));
        byte[] truncated = {-1, -1, -1, -1, 0x49, 0x11, 'A', 'B'};
        assertNull(A2sInfo.parse(truncated, truncated.length));
    }
}
