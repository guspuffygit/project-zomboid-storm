package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.StormPacketDivert;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;
import zombie.network.NetChecksum;
import zombie.network.PacketTypes;
import zombie.network.packets.service.ChecksumPacket;

/**
 * Client half of the Lua checksum exchange over TCP. Vanilla drives a request/reply state machine
 * ({@code NetChecksum.Comparer}) with {@code ChecksumPacket} sends nested inside its own parse
 * code; this class keeps that code and only swaps the transport. A {@link StormPacketDivert.Scope}
 * captures each outgoing packet, {@code POST /storm/game/checksum} carries it, and the reply is fed
 * to the vanilla {@link ChecksumPacket#parse}, which may emit the next request into the same scope.
 * The loop ends when a parse emits nothing (Success or Failed).
 *
 * <p>Fail-soft: a transport failure resets the comparer to {@code Init} and returns {@code false},
 * so the vanilla {@code beginCompare} body runs and the UDP exchange starts from scratch.
 */
public final class StormChecksumOverTcp {

    private static final String PATH = "/storm/game/checksum";

    /** Groups of 20 files in batches of 10; a few hundred rounds covers any mod list. */
    static final int MAX_ROUNDS = 512;

    private StormChecksumOverTcp() {}

    /** From {@code NetChecksum.Comparer.beginCompare()} on the loader thread. */
    public static boolean tryCompare() {
        UdpConnection connection = GameClient.connection;
        if (!StormTcpChannel.isEstablished() || connection == null) {
            return false;
        }
        NetChecksum.Comparer comparer = NetChecksum.comparer;
        comparer.error = null;
        long started = System.currentTimeMillis();
        try (StormPacketDivert.Scope scope =
                StormPacketDivert.open(connection, PacketTypes.PacketType.Checksum)) {
            ChecksumPacket.sendTotalChecksum();
            if (scope.pending() == 0) {
                // Divert unavailable: the total checksum already left over UDP and vanilla's
                // packet handler will drive the rest.
                LOGGER.info("Checksum packet was not captured; exchange continues over UDP");
                return true;
            }
            int rounds =
                    drive(
                            scope::takeOne,
                            StormChecksumOverTcp::post,
                            reply -> applyReply(reply, connection),
                            MAX_ROUNDS);
            LOGGER.info(
                    "Lua checksum exchange finished over TCP in {} round(s), {}ms, state {}",
                    rounds,
                    System.currentTimeMillis() - started,
                    comparer.state);
            return true;
        } catch (Throwable t) {
            LOGGER.info("Checksum over TCP failed ({}); falling back to UDP", t.toString());
            comparer.state = NetChecksum.Comparer.State.Init;
            comparer.currentIndex = 0;
            comparer.error = null;
            return false;
        }
    }

    /**
     * Runs the exchange until {@code nextRequest} yields nothing. {@code transport} returns the
     * reply bytes or {@code null}; a null reply or more than {@code maxRounds} rounds throws.
     * Returns the number of rounds completed.
     */
    static int drive(
            Supplier<byte[]> nextRequest,
            Function<byte[], byte[]> transport,
            Consumer<byte[]> applyReply,
            int maxRounds) {
        int rounds = 0;
        byte[] request = nextRequest.get();
        while (request != null) {
            if (++rounds > maxRounds) {
                throw new IllegalStateException(
                        "checksum exchange exceeded " + maxRounds + " rounds");
            }
            byte[] reply = transport.apply(request);
            if (reply == null) {
                throw new IllegalStateException("no reply in round " + rounds);
            }
            applyReply.accept(reply);
            request = nextRequest.get();
        }
        return rounds;
    }

    private static @Nullable byte[] post(byte[] request) {
        HttpRequest.Builder builder = StormTcpChannel.authenticatedRequest(PATH);
        if (builder == null) {
            return null;
        }
        try {
            HttpResponse<byte[]> response =
                    StormTcpChannel.send(
                            builder.header("Content-Type", "application/octet-stream")
                                    .POST(HttpRequest.BodyPublishers.ofByteArray(request))
                                    .build());
            if (response.statusCode() != 200) {
                LOGGER.info("TCP checksum exchange returned {}", response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            LOGGER.info("TCP checksum exchange failed ({})", e.toString());
            return null;
        }
    }

    private static void applyReply(byte[] reply, UdpConnection connection) {
        new ChecksumPacket().parse(new ByteBufferReader(ByteBuffer.wrap(reply)), connection);
    }
}
