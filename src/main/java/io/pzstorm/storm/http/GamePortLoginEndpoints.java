package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.StormTcpLogin;
import io.pzstorm.storm.connection.StormTcpSessionRegistry;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import zombie.core.raknet.UdpConnection;

/**
 * LoginPacket over the game-port TCP channel. Vanilla sends the Login once over UDP with no
 * acknowledgement, no retry and no deadline; a lost datagram leaves the client on "Connecting…"
 * until the server's 5 s pre-login reap drops it. Over TCP the response is the acknowledgement, the
 * client retries against an idempotent handler, and the reply carries every packet vanilla answers
 * the login with — see {@link StormTcpLogin}.
 *
 * <p>No capability gate: the connection has no role until this very packet assigns one. The session
 * binding (steamId + source IP, pre-login match on RakNet's own peer data) is the whole
 * authorization, and it only ever lets a caller log in the connection it shares an IP and steamId
 * with.
 */
public class GamePortLoginEndpoints {

    /** Three length-prefixed strings and an int; anything shorter cannot be a LoginPacket. */
    static final int MIN_LOGIN_BYTES = 3 * Short.BYTES + Integer.BYTES;

    @GameHttpEndpoint(path = "/storm/game/login", method = "POST")
    public static void login(HttpRequestEvent event) throws IOException {
        Session session = GamePortHandshakeEndpoints.requireSession(event);
        if (session == null) {
            return;
        }
        UdpConnection connection = StormTcpSessionRegistry.liveConnection(session);
        if (connection == null) {
            event.send(403, "game connection gone");
            return;
        }
        byte[] body = event.getRequestBody(StormTcpLogin.MAX_LOGIN_BYTES);
        if (body == null) {
            event.send(413, "login packet too large");
            return;
        }
        if (body.length < MIN_LOGIN_BYTES) {
            event.send(400, "login packet too short");
            return;
        }
        try {
            byte[] frames = StormTcpLogin.login(connection, body);
            event.setContentType("application/octet-stream");
            event.send(200, frames);
        } catch (TimeoutException e) {
            event.send(503, "server busy");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StormTcpLogin.MalformedLoginException) {
                event.send(400, "malformed login packet");
            } else if (cause instanceof StormTcpLogin.AlreadyLoggedInException) {
                event.send(409, "connection already logged in");
            } else {
                LOGGER.error("Login over TCP failed for {}", connection.getIDStr(), cause);
                event.send(500, "login failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.send(503, "server busy");
        }
    }
}
