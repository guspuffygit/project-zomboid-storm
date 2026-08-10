package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.sun.net.httpserver.HttpServer;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnServerStartedEvent;
import io.pzstorm.storm.util.StormEnv;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;
import zombie.network.GameServer;

/**
 * Owns the game-port HTTP server: a TCP listener on the same port number as the game's UDP port
 * ({@code DefaultPort}), for game communication with clients. TCP and UDP port spaces are
 * independent, so this coexists with RakNet's UDP bind. Requests are delegated to {@link
 * GameHttpEndpointDispatcher} — a registry fully separate from the backend server's, so backend
 * endpoints are never exposed here.
 *
 * <p>Starts on {@link OnServerStartedEvent} (fired by vanilla right after RakNet binds, when {@code
 * GameServer.defaultPort} is final), server JVMs only. On by default; disable with {@code
 * -Dstorm.gameport.http.enabled=false}. Fails soft: a refused bind logs an error and the server
 * runs without the TCP channel.
 *
 * <p>Note for operators: firewall and port-forward rules are per-protocol. An existing UDP rule for
 * the game port does not open TCP — the TCP side must be allowed separately or clients outside the
 * host cannot reach these endpoints.
 */
public class GamePortHttpServer {

    public static final String ENABLED_PROPERTY = "storm.gameport.http.enabled";

    /**
     * The game port is publicly listed, so expect junk scanner traffic: a small bounded pool keeps
     * one slow client from stalling every other request without giving abuse unbounded threads.
     */
    private static final int HANDLER_THREADS = 4;

    private static final int SOCKET_BACKLOG = 64;

    private static volatile HttpServer server;
    private static volatile ExecutorService handlerPool;
    private static volatile int boundPort = -1;
    private static final AtomicBoolean shutdownHookInstalled = new AtomicBoolean();

    private GamePortHttpServer() {}

    @SubscribeEvent
    public static void onServerStarted(OnServerStartedEvent event) {
        if (!StormEnv.isStormServer()) {
            return;
        }
        if (!isEnabled()) {
            LOGGER.info("Storm game-port HTTP server disabled via -D{}=false", ENABLED_PROPERTY);
            return;
        }
        String ip = GameServer.ipCommandline;
        int port = GameServer.defaultPort;
        start(ip != null ? new InetSocketAddress(ip, port) : new InetSocketAddress(port));
    }

    /**
     * Start the server on the given address if it isn't already running. A single root context
     * {@code /} routes every request through {@link GameHttpEndpointDispatcher}.
     */
    public static synchronized void start(InetSocketAddress address) {
        if (server != null) {
            LOGGER.debug("Storm game-port HTTP server already running on port {}", boundPort);
            return;
        }
        try {
            HttpServer created = HttpServer.create(address, SOCKET_BACKLOG);
            created.createContext("/", GameHttpEndpointDispatcher::dispatch);
            ExecutorService pool = newHandlerPool();
            created.setExecutor(pool);
            startWithDaemonDispatcher(created);
            server = created;
            handlerPool = pool;
            boundPort = created.getAddress().getPort();
            installShutdownHook();
            LOGGER.info("Storm game-port HTTP server started on TCP port {}", boundPort);
        } catch (IOException e) {
            LOGGER.error(
                    "Failed to start Storm game-port HTTP server on {} — game-port endpoints"
                            + " will be unavailable",
                    address,
                    e);
        }
    }

    /**
     * See {@link StormHttpServer}: the JDK's HTTP-Dispatcher thread inherits daemon status from the
     * starting thread and must be a daemon so a dead game's JVM is never pinned alive. Here the
     * caller is the server main thread (non-daemon), so the same starter dance applies.
     */
    private static void startWithDaemonDispatcher(HttpServer created) {
        Thread starter = new Thread(created::start, "storm-gameport-http-start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService newHandlerPool() {
        AtomicInteger threadNumber = new AtomicInteger();
        return Executors.newFixedThreadPool(
                HANDLER_THREADS,
                runnable -> {
                    Thread thread =
                            new Thread(
                                    runnable,
                                    "storm-gameport-http-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(GamePortHttpServer::stop));
        }
    }

    public static synchronized void stop() {
        HttpServer s = server;
        if (s == null) {
            return;
        }
        LOGGER.info("Stopping Storm game-port HTTP server on port {}", boundPort);
        s.stop(1);
        ExecutorService pool = handlerPool;
        if (pool != null) {
            pool.shutdown();
        }
        server = null;
        handlerPool = null;
        boundPort = -1;
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static int getPort() {
        return boundPort;
    }

    /** True unless {@value #ENABLED_PROPERTY} is explicitly set to {@code false}. */
    public static boolean isEnabled() {
        @Nullable String raw = System.getProperty(ENABLED_PROPERTY);
        return raw == null || !raw.trim().equalsIgnoreCase("false");
    }
}
