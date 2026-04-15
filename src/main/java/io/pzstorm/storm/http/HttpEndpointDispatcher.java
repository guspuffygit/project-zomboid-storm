package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Registry and dispatcher for {@link HttpEndpoint}-annotated handler methods. Exact path matching
 * only; query strings are read from the event but not used for routing.
 */
public class HttpEndpointDispatcher {

    private static final Map<String, HandlerMethod> HANDLERS = new HashMap<>();

    /**
     * Paths that exist for any method, used to distinguish 404 (no path) from 405 (wrong method).
     */
    private static final Set<String> KNOWN_PATHS = new HashSet<>();

    public static void reset() {
        HANDLERS.clear();
        KNOWN_PATHS.clear();
    }

    public static void registerHandler(Method method, @Nullable Object handler) {
        HttpEndpoint annotation = method.getAnnotation(HttpEndpoint.class);
        String path = annotation.path();
        String httpMethod = annotation.method().toUpperCase(Locale.ROOT);
        String key = key(httpMethod, path);

        HandlerMethod existing = HANDLERS.get(key);
        if (existing != null) {
            LOGGER.warn(
                    "Duplicate @HttpEndpoint for {} {}: {}.{} replaces {}.{}",
                    httpMethod,
                    path,
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    existing.method.getDeclaringClass().getSimpleName(),
                    existing.method.getName());
        }
        HANDLERS.put(key, new HandlerMethod(method, handler));
        KNOWN_PATHS.add(path);

        LOGGER.debug(
                "Registered @HttpEndpoint handler: {} {} -> {}.{}",
                httpMethod,
                path,
                method.getDeclaringClass().getSimpleName(),
                method.getName());
    }

    /**
     * Entry point invoked by {@link StormHttpServer}. Resolves the handler, invokes it, and sends a
     * 404, 405, or 500 if the handler is missing or throws.
     */
    public static void dispatch(HttpExchange exchange) {
        String httpMethod = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        HandlerMethod handler = HANDLERS.get(key(httpMethod, path));

        if (handler == null) {
            int status = KNOWN_PATHS.contains(path) ? 405 : 404;
            sendStatus(exchange, status);
            return;
        }

        HttpRequestEvent event = new HttpRequestEvent(exchange);
        try {
            handler.invoke(event);
            if (!event.wasResponseSent()) {
                event.sendEmpty(204);
            }
        } catch (Throwable t) {
            LOGGER.error(
                    "@HttpEndpoint handler {}.{} threw while serving {} {}",
                    handler.method.getDeclaringClass().getSimpleName(),
                    handler.method.getName(),
                    httpMethod,
                    path,
                    t);
            if (!event.wasResponseSent()) {
                sendStatus(exchange, 500);
            }
        } finally {
            exchange.close();
        }
    }

    private static void sendStatus(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException e) {
            LOGGER.error("Failed to send status {} response", status, e);
        }
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }

    private static class HandlerMethod {
        private final Method method;
        private final @Nullable Object handler;

        private HandlerMethod(Method method, @Nullable Object handler) {
            this.method = method;
            this.handler = handler;
        }

        private void invoke(HttpRequestEvent event) throws Throwable {
            try {
                method.invoke(handler, event);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
