package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Owns the single shared {@link HttpServer} instance for Storm and its mods. Opt-in via {@code
 * -Dstorm.http.port=<port>}; if the property is absent or not a positive integer, the server never
 * starts. All requests are delegated to {@link HttpEndpointDispatcher}.
 */
public class StormHttpServer {

    public static final String PORT_PROPERTY = "storm.http.port";

    private static volatile HttpServer server;
    private static volatile int boundPort = -1;

    private StormHttpServer() {}

    /**
     * Start the server on the given port if it isn't already running. A single root context {@code
     * /} routes every request through {@link HttpEndpointDispatcher}.
     */
    public static synchronized void start(int port) {
        if (server != null) {
            LOGGER.debug("Storm HTTP server already running on port {}", boundPort);
            return;
        }
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(port), 0);
            created.createContext("/", HttpEndpointDispatcher::dispatch);
            created.start();
            server = created;
            boundPort = port;
            LOGGER.info("Storm HTTP server started on port {}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to start Storm HTTP server on port {}", port, e);
        }
    }

    public static synchronized void stop() {
        HttpServer s = server;
        if (s == null) {
            return;
        }
        LOGGER.info("Stopping Storm HTTP server on port {}", boundPort);
        s.stop(1);
        server = null;
        boundPort = -1;
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static int getPort() {
        return boundPort;
    }

    /** Read {@value #PORT_PROPERTY} and return the port, or {@code -1} if unset/invalid. */
    public static int configuredPort() {
        String raw = System.getProperty(PORT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 ? port : -1;
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid {} value '{}', HTTP server will not start", PORT_PROPERTY, raw);
            return -1;
        }
    }
}
