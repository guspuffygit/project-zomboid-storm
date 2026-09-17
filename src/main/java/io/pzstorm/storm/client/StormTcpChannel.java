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
 * <p>The server binds the handshake either to the connection whose LoginPacket it already processed
 * or, before that, to the one pre-login RakNet connection from this IP (and steamId, in Steam
 * mode). A rejection (403) still happens when RakNet has not surfaced the connection on the server
 * yet, so it is retried until {@link #REJECTED_WINDOW_MILLIS} after the first attempt; an
 * unreachable listener (no Storm, TCP unforwarded) is capped at {@link #MAX_UNREACHABLE_ATTEMPTS}
 * and remembered per host:port so {@link StormLoginOverTcp} stops holding the Login for it. On
 * disconnect the session clears and the watcher arms again for the next connection.
 */
public final class StormTcpChannel {

    /** Session header name; mirrors StormTcpSessionRegistry.SESSION_HEADER on the server. */
    public static final String SESSION_HEADER = "X-Storm-Session";

    private static final int POLL_INTERVAL_MILLIS = 250;
    private static final int HANDSHAKE_RETRY_MILLIS = 500;
    private static final int MAX_UNREACHABLE_ATTEMPTS = 5;
    private static final long REJECTED_WINDOW_MILLIS = 120_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    public record Session(String baseUrl, String token, String serverStormVersion) {}

    enum Outcome {
        ESTABLISHED,
        REJECTED,
        UNREACHABLE
    }

    /**
     * Per-connection retry budget. Rejections are retried on a wall clock, since they normally mean
     * "server has not processed the login yet"; unreachable results are counted, since they mean
     * the server has no listener at all.
     */
    static final class Budget {
        private final int maxUnreachable;
        private final long rejectedWindowMillis;
        private int unreachable;
        private long firstAttemptAt = -1;
        @Nullable String lastFailure;

        Budget(int maxUnreachable, long rejectedWindowMillis) {
            this.maxUnreachable = maxUnreachable;
            this.rejectedWindowMillis = rejectedWindowMillis;
        }

        /** Record one failed attempt; returns true when the watcher should stop trying. */
        boolean exhausted(Outcome outcome, String failure, long nowMillis) {
            lastFailure = failure;
            if (firstAttemptAt < 0) {
                firstAttemptAt = nowMillis;
            }
            return switch (outcome) {
                case ESTABLISHED -> false;
                case UNREACHABLE -> ++unreachable >= maxUnreachable;
                case REJECTED -> nowMillis - firstAttemptAt >= rejectedWindowMillis;
            };
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile @Nullable Session session;
    private static volatile @Nullable HttpClient httpClient;
    private static volatile @Nullable Thread watcher;
    private static volatile @Nullable String unavailableServer;

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
     * True while a handshake to the current game host may still succeed: the watcher is running and
     * this host:port has not exhausted its unreachable budget on this connection or an earlier one.
     */
    public static boolean mayEstablish() {
        if (watcher == null) {
            return false;
        }
        String host = GameClient.ip;
        int port = GameClient.port;
        return host != null && !(host + ":" + port).equals(unavailableServer);
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
        Budget budget = newBudget();
        boolean gaveUp = false;
        long nextAttemptAt = 0;
        while (true) {
            try {
                boolean connected = GameClient.connection != null;
                if (connected && !wasConnected) {
                    budget = newBudget();
                    gaveUp = false;
                    nextAttemptAt = 0;
                }
                if (!connected) {
                    if (session != null) {
                        LOGGER.info("Storm TCP channel closed (game connection dropped)");
                    }
                    session = null;
                    if (wasConnected) {
                        StormLoginOverTcp.reset();
                    }
                }
                long now = System.currentTimeMillis();
                if (connected && session == null && !gaveUp && now >= nextAttemptAt) {
                    Attempt attempt = attemptHandshake();
                    nextAttemptAt = now + HANDSHAKE_RETRY_MILLIS;
                    if (attempt.outcome() == Outcome.ESTABLISHED) {
                        StormLoginOverTcp.onSessionEstablished();
                    } else if (budget.exhausted(attempt.outcome(), attempt.detail(), now)) {
                        gaveUp = true;
                        if (attempt.outcome() == Outcome.UNREACHABLE) {
                            unavailableServer = GameClient.ip + ":" + GameClient.port;
                        }
                        LOGGER.info(
                                "Storm TCP channel unavailable for this server; staying on UDP"
                                        + " (last failure: {})",
                                budget.lastFailure);
                    }
                }
                if (connected) {
                    StormLoginOverTcp.tick(now);
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

    private static Budget newBudget() {
        return new Budget(MAX_UNREACHABLE_ATTEMPTS, REJECTED_WINDOW_MILLIS);
    }

    record Attempt(Outcome outcome, String detail) {}

    private static Attempt attemptHandshake() {
        String host = GameClient.ip;
        int port = GameClient.port;
        if (host == null || host.isBlank() || port <= 0) {
            return new Attempt(Outcome.UNREACHABLE, "no game host/port yet");
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
                String detail = response.statusCode() + " " + response.body();
                LOGGER.debug("Storm TCP handshake to {} rejected: {}", baseUrl, detail);
                return new Attempt(Outcome.REJECTED, detail);
            }
            JsonNode json = MAPPER.readTree(response.body());
            Session established =
                    new Session(
                            baseUrl,
                            json.get("sessionToken").asText(),
                            json.path("serverStormVersion").asText("unknown"));
            unavailableServer = null;
            session = established;
            LOGGER.info(
                    "Storm TCP channel established to {} (server Storm {})",
                    baseUrl,
                    established.serverStormVersion());
            return new Attempt(Outcome.ESTABLISHED, "");
        } catch (Exception e) {
            LOGGER.debug("Storm TCP handshake to {} failed: {}", baseUrl, e.toString());
            return new Attempt(Outcome.UNREACHABLE, e.toString());
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
