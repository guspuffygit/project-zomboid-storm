package io.pzstorm.storm.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormClassLoaderResourceTest {

    @Test
    void shouldReadAllBytesAndCloseResourceOnce() throws IOException {
        byte[] bytes = new byte[20_001];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        TrackingInputStream stream = new TrackingInputStream(bytes);

        Assertions.assertArrayEquals(bytes, loader(stream).getRawClassByteArray("example.Model"));
        Assertions.assertEquals(1, stream.closeCalls);
        Assertions.assertTrue(stream.readCalls > 2);
    }

    @Test
    void shouldCloseResourceWhenAvailableFails() {
        TrackingInputStream stream = new TrackingInputStream(new byte[1]);
        stream.availableFailure = new IOException("available failed");

        IOException failure =
                Assertions.assertThrows(
                        IOException.class,
                        () -> loader(stream).getRawClassByteArray("example.Model"));

        Assertions.assertSame(stream.availableFailure, failure);
        Assertions.assertEquals(1, stream.closeCalls);
        Assertions.assertEquals(0, stream.readCalls);
    }

    @Test
    void shouldCloseResourceAfterPartialReadAndPreserveReadFailure() {
        TrackingInputStream stream = new TrackingInputStream(new byte[1_024]);
        stream.readFailure = new IOException("read failed");
        stream.closeFailure = new IOException("close failed");

        IOException failure =
                Assertions.assertThrows(
                        IOException.class,
                        () -> loader(stream).getRawClassByteArray("example.Model"));

        Assertions.assertSame(stream.readFailure, failure);
        Assertions.assertEquals(1, stream.closeCalls);
        Assertions.assertEquals(2, stream.readCalls);
        Assertions.assertArrayEquals(
                new Throwable[] {stream.closeFailure}, failure.getSuppressed());
    }

    @Test
    void shouldStillReportCloseFailureAfterSuccessfulRead() {
        TrackingInputStream stream = new TrackingInputStream(new byte[1]);
        stream.closeFailure = new IOException("close failed");

        IOException failure =
                Assertions.assertThrows(
                        IOException.class,
                        () -> loader(stream).getRawClassByteArray("example.Model"));

        Assertions.assertSame(stream.closeFailure, failure);
        Assertions.assertEquals(1, stream.closeCalls);
    }

    @Test
    void shouldReturnEmptyBytesForMissingResource() throws IOException {
        Assertions.assertArrayEquals(
                new byte[0], loader(null).getRawClassByteArray("example.Missing"));
    }

    private static StormClassLoader loader(InputStream stream) {
        return new StormClassLoader(new URL[0]) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return stream;
            }
        };
    }

    private static final class TrackingInputStream extends InputStream {
        private final byte[] bytes;
        private int position;
        private int readCalls;
        private int closeCalls;
        private IOException availableFailure;
        private IOException readFailure;
        private IOException closeFailure;

        private TrackingInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int available() throws IOException {
            if (availableFailure != null) {
                throw availableFailure;
            }
            return bytes.length - position;
        }

        @Override
        public int read() {
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            readCalls++;
            if (readFailure != null && readCalls > 1) {
                throw readFailure;
            }
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, 512), bytes.length - position);
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
