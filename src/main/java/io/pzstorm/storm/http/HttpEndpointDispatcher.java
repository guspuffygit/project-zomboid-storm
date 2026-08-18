package io.pzstorm.storm.http;

import com.sun.net.httpserver.HttpExchange;
import io.pzstorm.storm.metrics.HttpEndpointMetrics;
import java.lang.reflect.Method;
import org.jetbrains.annotations.Nullable;

/**
 * Registry and dispatcher for {@link HttpEndpoint}-annotated handler methods served by Storm's
 * backend HTTP server ({@link StormHttpServer}). Routing, body binding, and error handling live in
 * {@link HttpEndpointRegistry}; this registry is separate from the game-port server's ({@link
 * GameHttpEndpointDispatcher}) so backend endpoints are never exposed on the game port.
 */
public class HttpEndpointDispatcher {

    private static final HttpEndpointRegistry REGISTRY =
            new HttpEndpointRegistry("@HttpEndpoint", HttpEndpointMetrics.STORM);

    private HttpEndpointDispatcher() {}

    public static void reset() {
        REGISTRY.reset();
    }

    public static void registerHandler(Method method, @Nullable Object handler) {
        HttpEndpoint annotation = method.getAnnotation(HttpEndpoint.class);
        REGISTRY.registerHandler(annotation.method(), annotation.path(), method, handler);
    }

    /** Entry point invoked by {@link StormHttpServer}. */
    public static void dispatch(HttpExchange exchange) {
        REGISTRY.dispatch(exchange);
    }
}
