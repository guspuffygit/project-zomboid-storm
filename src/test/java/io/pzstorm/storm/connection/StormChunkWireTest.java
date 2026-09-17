package io.pzstorm.storm.connection;

import io.pzstorm.storm.UnitTest;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormChunkWireTest implements UnitTest {

    @Test
    void roundTripsAllEntryKinds() throws IOException {
        byte[] zip = {5, 4, 3, 2, 1, 0, -1};
        List<StormChunkWire.Entry> entries =
                List.of(
                        StormChunkWire.Entry.data(7, zip),
                        StormChunkWire.Entry.notRequired(8, true),
                        StormChunkWire.Entry.notRequired(9, false),
                        StormChunkWire.Entry.retry(10));

        List<StormChunkWire.Entry> parsed = StormChunkWire.read(StormChunkWire.write(entries));

        Assertions.assertEquals(4, parsed.size());
        Assertions.assertEquals(7, parsed.get(0).requestNumber());
        Assertions.assertEquals(StormChunkWire.KIND_DATA, parsed.get(0).kind());
        Assertions.assertArrayEquals(zip, parsed.get(0).data());
        Assertions.assertTrue(parsed.get(1).sameOnServer());
        Assertions.assertFalse(parsed.get(2).sameOnServer());
        Assertions.assertEquals(StormChunkWire.KIND_RETRY, parsed.get(3).kind());
    }

    @Test
    void emptyResponseRoundTrips() throws IOException {
        Assertions.assertTrue(StormChunkWire.read(StormChunkWire.write(List.of())).isEmpty());
    }

    @Test
    void truncatedBodyFailsInsteadOfMisparsing() {
        Assertions.assertThrows(
                IOException.class,
                () -> {
                    byte[] intact =
                            StormChunkWire.write(
                                    List.of(StormChunkWire.Entry.data(1, new byte[64])));
                    byte[] truncated = new byte[intact.length - 10];
                    System.arraycopy(intact, 0, truncated, 0, truncated.length);
                    StormChunkWire.read(truncated);
                });
    }

    @Test
    void dataLengthBeyondBodyIsRejected() {
        // A malicious/corrupt length prefix must not drive allocation past the body size.
        byte[] body = {0, 0, 0, 1, 0, 0, 0, 5, StormChunkWire.KIND_DATA, 127, 127, 127, 127};
        Assertions.assertThrows(IOException.class, () -> StormChunkWire.read(body));
    }
}
