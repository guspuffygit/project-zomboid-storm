package io.pzstorm.launcher;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal Valve A2S_INFO query. A Steam-mode Project Zomboid server answers Valve's server-query
 * protocol on the game UDP port players already join on, so this needs no extra port and no login —
 * one datagram round trip (two when the server demands the anti-spoof challenge first), cheap
 * enough to poll for a live player count.
 *
 * <p>Hand-rolled on purpose: the launcher may not touch Project Zomboid classes, and unlike {@link
 * ServerQuery} this must not cost a child JVM per poll. Every failure — timeout, closed port,
 * malformed reply — is soft and returns null.
 */
public final class A2sInfo {

    private static final byte[] QUERY_PREFIX = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    private static final byte HEADER_INFO_REQUEST = 0x54;
    private static final byte HEADER_INFO_REPLY = 0x49;
    private static final byte HEADER_CHALLENGE = 0x41;
    private static final byte[] INFO_PAYLOAD =
            "Source Engine Query\0".getBytes(StandardCharsets.US_ASCII);

    private A2sInfo() {}

    public static final class Result {
        public final String serverName;
        public final int players;
        public final int maxPlayers;

        Result(String serverName, int players, int maxPlayers) {
            this.serverName = serverName;
            this.players = players;
            this.maxPlayers = maxPlayers;
        }
    }

    /** Queries the server, or returns null when it did not answer within {@code timeoutMillis}. */
    public static Result query(String host, int port, int timeoutMillis) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMillis);
            InetSocketAddress address = new InetSocketAddress(host, port);
            byte[] reply = exchange(socket, address, request(null));
            if (reply != null && reply.length >= 9 && reply[4] == HEADER_CHALLENGE) {
                reply = exchange(socket, address, request(Arrays.copyOfRange(reply, 5, 9)));
            }
            return reply == null ? null : parse(reply, reply.length);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] request(byte[] challenge) {
        int length = QUERY_PREFIX.length + 1 + INFO_PAYLOAD.length + (challenge == null ? 0 : 4);
        byte[] request = new byte[length];
        System.arraycopy(QUERY_PREFIX, 0, request, 0, QUERY_PREFIX.length);
        request[QUERY_PREFIX.length] = HEADER_INFO_REQUEST;
        System.arraycopy(INFO_PAYLOAD, 0, request, QUERY_PREFIX.length + 1, INFO_PAYLOAD.length);
        if (challenge != null) {
            System.arraycopy(challenge, 0, request, length - 4, 4);
        }
        return request;
    }

    private static byte[] exchange(DatagramSocket socket, InetSocketAddress address, byte[] request)
            throws IOException {
        socket.send(new DatagramPacket(request, request.length, address));
        byte[] buffer = new byte[1400];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return Arrays.copyOf(packet.getData(), packet.getLength());
    }

    /**
     * Parses an A2S_INFO reply: {@code FFFFFFFF 'I' protocol name\0 map\0 folder\0 game\0 id(2)
     * players(1) maxPlayers(1) ...}. Returns null on anything malformed.
     */
    static Result parse(byte[] data, int length) {
        if (length < 6 || data[0] != -1 || data[1] != -1 || data[2] != -1 || data[3] != -1) {
            return null;
        }
        if (data[4] != HEADER_INFO_REPLY) {
            return null;
        }
        int pos = 6; // past the four 0xFF, the 'I' header, and the protocol byte
        String name = null;
        for (int string = 0; string < 4; string++) {
            int start = pos;
            while (pos < length && data[pos] != 0) {
                pos++;
            }
            if (pos >= length) {
                return null;
            }
            if (string == 0) {
                name = new String(data, start, pos - start, StandardCharsets.UTF_8);
            }
            pos++;
        }
        if (pos + 4 > length) {
            return null;
        }
        int players = data[pos + 2] & 0xFF;
        int maxPlayers = data[pos + 3] & 0xFF;
        return new Result(name, players, maxPlayers);
    }
}
