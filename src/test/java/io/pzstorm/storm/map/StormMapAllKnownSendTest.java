package io.pzstorm.storm.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

/**
 * Verifies the payload Storm sends in place of vanilla's {@code
 * WorldMapVisitedServer.sendRequestData} while {@code Map.MapAllKnown} is on: every unit comes out
 * known, already-visited units stay visited, and the deflate framing round-trips through the same
 * {@code Inflater} shape the client's {@code WorldMapVisited.receiveRequestData} uses.
 */
class StormMapAllKnownSendTest implements UnitTest {

    private static final int BIT_VISITED = 1;
    private static final int BIT_KNOWN = 2;

    @Test
    void allKnownSetsKnownBitOnEveryUnitAndKeepsVisitedBits() {
        byte[] stored = new byte[] {0x00, 0x55, (byte) 0xFF, 0x11};

        byte[] entity = StormMapAllKnownSend.allKnown(stored);

        assertEquals(stored.length, entity.length);
        for (int i = 0; i < entity.length; i++) {
            for (int unit = 0; unit < 4; unit++) {
                int before = stored[i] >> unit * 2 & 3;
                int after = entity[i] >> unit * 2 & 3;
                assertEquals(
                        BIT_KNOWN,
                        after & BIT_KNOWN,
                        "unit " + unit + " of byte " + i + " must be known");
                assertEquals(
                        before & BIT_VISITED,
                        after & BIT_VISITED,
                        "unit " + unit + " of byte " + i + " must keep its visited bit");
            }
        }
    }

    @Test
    void allKnownDoesNotMutateTheStoredArray() {
        byte[] stored = new byte[] {0x00, 0x55, (byte) 0xFF, 0x11};
        byte[] original = stored.clone();

        StormMapAllKnownSend.allKnown(stored);

        assertArrayEquals(
                original,
                stored,
                "the server's per-user data must survive untouched, otherwise turning the option"
                        + " off would leave players with a permanently revealed map");
    }

    @Test
    void deflateRoundTripsThroughTheClientInflaterShape() throws Exception {
        byte[] entity = StormMapAllKnownSend.allKnown(pseudoVisitedData(64 * 1024));
        byte[] compressed = new byte[entity.length + entity.length / 8 + 64];

        int compressedLength = StormMapAllKnownSend.deflate(entity, compressed);

        assertTrue(compressedLength > 0, "all-known data must fit vanilla's output buffer");
        assertArrayEquals(entity, inflateLikeClient(compressed, compressedLength, entity.length));
    }

    /** Mirrors {@code WorldMapVisited.receiveRequestData}. */
    private static byte[] inflateLikeClient(byte[] compressed, int compressedLength, int length)
            throws Exception {
        byte[] visited = new byte[length];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed, 0, compressedLength);
            int position = 0;
            while (!inflater.finished() && position < length) {
                int count = inflater.inflate(visited, position, length - position);
                if (count == 0) {
                    break;
                }
                position += count;
            }
            assertEquals(length, position, "client must decode the whole visited array");
        } finally {
            inflater.end();
        }
        return visited;
    }

    private static byte[] pseudoVisitedData(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i % 97 == 0 ? 0x55 : 0x00);
        }
        return data;
    }
}
