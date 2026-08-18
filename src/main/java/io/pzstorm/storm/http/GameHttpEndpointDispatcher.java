package io.pzstorm.storm.http;

import com.sun.net.httpserver.HttpExchange;
import io.pzstorm.storm.metrics.HttpEndpointMetrics;
import java.lang.reflect.Method;
import org.jetbrains.annotations.Nullable;

/**
 * Registry and dispatcher for {@link GameHttpEndpoint}-annotated handler methods served by the
 * game-port HTTP server ({@link GamePortHttpServer}). Routing, body binding, and error handling
 * live in {@link HttpEndpointRegistry}; this registry is separate from the backend server's ({@link
 * HttpEndpointDispatcher}) so backend endpoints are never exposed on the internet-facing game port.
 */
public class GameHttpEndpointDispatcher {

    private static final HttpEndpointRegistry REGISTRY =
            new HttpEndpointRegistry("@GameHttpEndpoint", HttpEndpointMetrics.GAME_PORT);

    private GameHttpEndpointDispatcher() {}

    public static void reset() {
        REGISTRY.reset();
    }

    public static void registerHandler(Method method, @Nullable Object handler) {
        GameHttpEndpoint annotation = method.getAnnotation(GameHttpEndpoint.class);
        REGISTRY.registerHandler(annotation.method(), annotation.path(), method, handler);
    }

    /** Entry point invoked by {@link GamePortHttpServer}. */
    public static void dispatch(HttpExchange exchange) {
        REGISTRY.dispatch(exchange);
    }
}
