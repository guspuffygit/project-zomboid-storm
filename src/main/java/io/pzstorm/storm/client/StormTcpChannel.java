package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.core.StormVersion;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import org.jetbrains.annotations.Nullable;
import zombie.core.random.RandAbstract;
import zombie.core.random.RandStandard;
import zombie.core.znet.SteamUser;
import zombie.network.GameClient;

/**
 * Client side of the game-port TCP channel. A watcher thread polls for an established RakNet
 * connection ({@link GameClient#connection}); once the client is connected over UDP it dials TCP on
 * the same host:port it just dialed over UDP ({@link GameClient#ip}:{@link GameClient#port}) and
 * performs {@code POST /storm/handshake}, marking this client as a Storm connection on the server
 * and obtaining the session token that authenticates all further game-port requests.
 *
 * <p>Polling instead of event hooks is deliberate: the handshake must run during early connect,
 * before the Lua VM (and therefore the Lua event bridge) is loaded, and a poll every {@value
 * #POLL_INTERVAL_MILLIS}ms costs nothing. Everything here fails soft — a server without Storm, with
 * the game-port server disabled, or with TCP unforwarded leaves the client on plain UDP.
 *
 * <p>Handshake attempts are capped per connection; on disconnect the session clears and the watcher
 * arms again for the next connection.
 */
public final class StormTcpChannel {

    /** Session header name; mirrors StormTcpSessionRegistry.SESSION_HEADER on the server. */
    public static final String SESSION_HEADER = "X-Storm-Session";

    private static final int POLL_INTERVAL_MILLIS = 250;
    private static final int MAX_HANDSHAKE_ATTEMPTS = 5;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    public record Session(String baseUrl, String token, String serverStormVersion) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile @Nullable Session session;
    private static volatile @Nullable HttpClient httpClient;
    private static @Nullable Thread watcher;

    private StormTcpChannel() {}

    /** Idempotently start the watcher thread. Called from StormLauncher on client JVMs. */
    public static synchronized void start() {
        if (watcher != null) {
            return;
        }
        Thread thread = new Thread(StormTcpChannel::watch, "storm-tcp-channel");
        thread.setDaemon(true);
        thread.start();
        watcher = thread;
        LOGGER.debug("Storm TCP channel watcher started");
    }

    /** The established session, or {@code null} while unconnected / on a non-Storm server. */
    public static @Nullable Session getSession() {
        return session;
    }

    public static boolean isEstablished() {
        return session != null;
    }

    /**
     * Build an authenticated request for the current session, or {@code null} if there is none.
     * Callers add their own method/body and send via {@link #send}.
     */
    @Nullable
    public static HttpRequest.Builder authenticatedRequest(String path) {
        Session current = session;
        if (current == null) {
            return null;
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(current.baseUrl() + path))
                .timeout(REQUEST_TIMEOUT)
                .header(SESSION_HEADER, current.token());
    }

    public static HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        return client().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void watch() {
        if (!awaitGameRngInit()) {
            return;
        }
        boolean wasConnected = false;
        int attempts = 0;
        while (true) {
            try {
                boolean connected = GameClient.connection != null;
                if (connected && !wasConnected) {
                    attempts = 0;
                }
                if (!connected) {
                    if (session != null) {
                        LOGGER.info("Storm TCP channel closed (game connection dropped)");
                    }
                    session = null;
                }
                if (connected && session == null && attempts < MAX_HANDSHAKE_ATTEMPTS) {
                    attempts++;
                    if (!attemptHandshake() && attempts == MAX_HANDSHAKE_ATTEMPTS) {
                        LOGGER.info(
                                "Storm TCP channel unavailable for this server (after {}"
                                        + " attempts); staying on UDP",
                                attempts);
                    }
                }
                wasConnected = connected;
            } catch (Throwable t) {
                // The watcher must never die; a broken channel just means UDP-only.
                LOGGER.error("Storm TCP channel watcher error", t);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Block until the game main thread has initialized its RNG — the first thing {@code
     * MainScreenState.main} does. Reading {@link GameClient#connection} any earlier
     * class-initializes {@link GameClient} on this thread, and its static initializer draws from
     * the RNG (via ServerOptions defaults): losing that race throws inside {@code
     * GameClient.<clinit>}, which poisons the class for the whole JVM and crashes the client at its
     * first debug print (vanilla touches GameClient right after RNG init). The RNG field is located
     * by type rather than name so a decompiler rename can't silently break the gate.
     *
     * @return false if the channel must stay off (interrupted, or no RNG field after a game
     *     update); the client then simply stays on plain UDP.
     */
    static boolean awaitGameRngInit() {
        Field randField = null;
        for (Field field : RandAbstract.class.getDeclaredFields()) {
            if (field.getType() == Random.class) {
                randField = field;
                break;
            }
        }
        if (randField == null) {
            LOGGER.error("Storm TCP channel disabled: no RNG field on RandAbstract to gate on");
            return false;
        }
        randField.setAccessible(true);
        try {
            while (randField.get(RandStandard.INSTANCE) == null) {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            LOGGER.error("Storm TCP channel disabled: cannot read game RNG state", t);
            return false;
        }
    }

    private static boolean attemptHandshake() {
        String host = GameClient.ip;
        int port = GameClient.port;
        if (host == null || host.isBlank() || port <= 0) {
            return false;
        }
        String baseUrl = "http://" + host + ":" + port;
        try {
            String body =
                    MAPPER.writeValueAsString(
                            java.util.Map.of(
                                    "steamId", Long.toString(SteamUser.GetSteamID()),
                                    "stormVersion", StormVersion.getVersion()));
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/storm/handshake"))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.debug(
                        "Storm TCP handshake to {} rejected: {} {}",
                        baseUrl,
                        response.statusCode(),
                        response.body());
                return false;
            }
            JsonNode json = MAPPER.readTree(response.body());
            Session established =
                    new Session(
                            baseUrl,
                            json.get("sessionToken").asText(),
                            json.path("serverStormVersion").asText("unknown"));
            session = established;
            LOGGER.info(
                    "Storm TCP channel established to {} (server Storm {})",
                    baseUrl,
                    established.serverStormVersion());
            return true;
        } catch (Exception e) {
            LOGGER.debug("Storm TCP handshake to {} failed: {}", baseUrl, e.toString());
            return false;
        }
    }

    private static HttpClient client() {
        HttpClient current = httpClient;
        if (current == null) {
            synchronized (StormTcpChannel.class) {
                current = httpClient;
                if (current == null) {
                    current = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
                    httpClient = current;
                }
            }
        }
        return current;
    }
}
