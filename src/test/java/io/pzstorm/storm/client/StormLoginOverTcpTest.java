package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class StormLoginOverTcpTest implements UnitTest {

    @Test
    void decodeReadsEveryFrame() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.putShort((short) 12).putInt(3).put(new byte[] {1, 2, 3});
        buffer.putShort((short) 34).putInt(0);
        byte[] body = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(body);

        List<StormLoginOverTcp.Frame> frames = StormLoginOverTcp.decode(body);

        assertEquals(2, frames.size());
        assertEquals(12, frames.get(0).id());
        assertArrayEquals(new byte[] {1, 2, 3}, frames.get(0).payload());
        assertEquals(34, frames.get(1).id());
        assertEquals(0, frames.get(1).payload().length);
    }

    @Test
    void decodeOfEmptyBodyIsEmpty() throws Exception {
        assertTrue(StormLoginOverTcp.decode(new byte[0]).isEmpty());
    }

    @Test
    void decodeRejectsOversizedAndTruncatedFrames() {
        byte[] oversized = {0, 1, 0x7f, 0x7f, 0x7f, 0x7f, 1};
        assertThrows(
                StormLoginOverTcp.MalformedReplyException.class,
                () -> StormLoginOverTcp.decode(oversized));

        byte[] negative = {0, 1, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertThrows(
                StormLoginOverTcp.MalformedReplyException.class,
                () -> StormLoginOverTcp.decode(negative));

        byte[] truncatedHeader = {0, 1, 0};
        assertThrows(
                StormLoginOverTcp.MalformedReplyException.class,
                () -> StormLoginOverTcp.decode(truncatedHeader));
    }
}
