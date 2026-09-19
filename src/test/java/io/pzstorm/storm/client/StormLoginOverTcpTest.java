package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StormLoginOverTcpTest implements UnitTest {

    /**
     * Steam mode: vanilla sets {@code GameClient.connection} before it sends the Login, so the
     * handshake can finish first. The watcher's poll must still post a Login held after that, and
     * must post it once.
     */
    @Test
    void loginHeldAfterTheSessionExistsIsPostedOnceByTheNextPoll() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        withLoginServer(
                new byte[0],
                posts,
                () -> {
                    StormLoginOverTcp.hold(null, new byte[] {1, 2, 3});

                    StormTcpChannel.pollLogin(System.currentTimeMillis());
                    assertEquals(1, posts.get());

                    StormTcpChannel.pollLogin(System.currentTimeMillis());
                    assertEquals(1, posts.get(), "a delivered reply must not be posted again");
                });
    }

    /**
     * The main thread reports a malformed reply; until it has, a poll must not post a second time.
     */
    @Test
    void malformedReplyIsNotPostedAgainByTheNextPoll() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        withLoginServer(
                new byte[] {0, 1, 0},
                posts,
                () -> {
                    StormLoginOverTcp.hold(null, new byte[] {1, 2, 3});

                    StormTcpChannel.pollLogin(System.currentTimeMillis());
                    StormTcpChannel.pollLogin(System.currentTimeMillis());

                    assertEquals(1, posts.get());
                });
    }

    private static void withLoginServer(byte[] reply, AtomicInteger posts, Runnable body)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                StormLoginOverTcp.PATH,
                exchange -> {
                    posts.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, reply.length == 0 ? -1 : reply.length);
                    exchange.getResponseBody().write(reply);
                    exchange.close();
                });
        server.start();
        Field session = StormTcpChannel.class.getDeclaredField("session");
        session.setAccessible(true);
        try {
            session.set(
                    null,
                    new StormTcpChannel.Session(
                            "http://127.0.0.1:" + server.getAddress().getPort(), "token", "test"));
            body.run();
        } finally {
            session.set(null, null);
            StormLoginOverTcp.reset();
            server.stop(0);
        }
    }

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
