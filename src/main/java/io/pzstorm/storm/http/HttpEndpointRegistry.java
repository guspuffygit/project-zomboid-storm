package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.pzstorm.storm.metrics.HttpEndpointMetrics;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Registry and dispatch core shared by {@link HttpEndpointDispatcher} (Storm's backend HTTP server)
 * and {@link GameHttpEndpointDispatcher} (the internet-facing game-port HTTP server). Each server
 * owns its own instance, so an endpoint registered for one can never be served by the other.
 *
 * <p>Exact path matching only; query strings are read from the event but not used for routing.
 *
 * <p>Handler methods may declare a second parameter of any non-{@link HttpRequestEvent} type. That
 * parameter is treated as the JSON request body and is deserialized by Jackson before the handler
 * runs. The dispatcher rejects empty or malformed bodies with a 400 response, so handlers can
 * assume the bound argument is non-null and well-formed.
 */
class HttpEndpointRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Annotation name used in log messages, e.g. {@code @HttpEndpoint}. */
    private final String annotationName;

    private final HttpEndpointMetrics metrics;
    private final Map<String, HandlerMethod> handlers = new HashMap<>();

    /**
     * Paths that exist for any method, used to distinguish 404 (no path) from 405 (wrong method).
     */
    private final Set<String> knownPaths = new HashSet<>();

    HttpEndpointRegistry(String annotationName, HttpEndpointMetrics metrics) {
        this.annotationName = annotationName;
        this.metrics = metrics;
    }

    synchronized void reset() {
        handlers.clear();
        knownPaths.clear();
    }

    synchronized void registerHandler(
            String httpMethod, String path, Method method, @Nullable Object handler) {
        httpMethod = httpMethod.toUpperCase(Locale.ROOT);
        String key = key(httpMethod, path);

        HandlerMethod existing = handlers.get(key);
        if (existing != null) {
            LOGGER.warn(
                    "Duplicate {} for {} {}: {}.{} replaces {}.{}",
                    annotationName,
                    httpMethod,
                    path,
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    existing.method.getDeclaringClass().getSimpleName(),
                    existing.method.getName());
        }
        handlers.put(key, new HandlerMethod(method, handler));
        knownPaths.add(path);

        LOGGER.debug(
                "Registered {} handler: {} {} -> {}.{}",
                annotationName,
                httpMethod,
                path,
                method.getDeclaringClass().getSimpleName(),
                method.getName());
    }

    /**
     * Resolves the handler for the exchange, invokes it, and sends a 404, 405, or 500 if the
     * handler is missing or throws.
     */
    void dispatch(HttpExchange exchange) {
        long startNanos = System.nanoTime();
        String httpMethod = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        HandlerMethod handler;
        boolean knownPath;
        synchronized (this) {
            handler = handlers.get(key(httpMethod, path));
            knownPath = knownPaths.contains(path);
        }
        String metricPath = (handler != null || knownPath) ? path : "unknown";

        HttpRequestEvent event = null;
        int fallbackStatus = -1;
        try {
            if (handler == null) {
                fallbackStatus = knownPath ? 405 : 404;
                sendStatus(exchange, fallbackStatus);
                return;
            }

            event = new HttpRequestEvent(exchange);
            try {
                handler.invoke(event);
                if (!event.wasResponseSent()) {
                    event.sendEmpty(204);
                }
            } catch (Throwable t) {
                LOGGER.error(
                        "{} handler {}.{} threw while serving {} {}",
                        annotationName,
                        handler.method.getDeclaringClass().getSimpleName(),
                        handler.method.getName(),
                        httpMethod,
                        path,
                        t);
                if (!event.wasResponseSent()) {
                    fallbackStatus = 500;
                    sendStatus(exchange, fallbackStatus);
                }
            }
        } finally {
            int status =
                    (event != null && event.getResponseStatus() != -1)
                            ? event.getResponseStatus()
                            : fallbackStatus;
            if (status != -1) {
                metrics.recordRequest(httpMethod, metricPath, status);
            }
            metrics.recordDuration(httpMethod, metricPath, System.nanoTime() - startNanos);
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
        private final @Nullable Class<?> bodyType;

        private HandlerMethod(Method method, @Nullable Object handler) {
            this.method = method;
            this.handler = handler;
            Class<?>[] params = method.getParameterTypes();
            this.bodyType = params.length == 2 ? params[1] : null;
        }

        private void invoke(HttpRequestEvent event) throws Throwable {
            Object[] args;
            if (bodyType == null) {
                args = new Object[] {event};
            } else {
                String raw = event.getRequestBodyAsString();
                if (raw == null || raw.isBlank()) {
                    event.send(400, "missing request body");
                    return;
                }
                Object body;
                try {
                    body = MAPPER.readValue(raw, bodyType);
                } catch (JsonProcessingException e) {
                    event.send(400, "invalid JSON: " + e.getOriginalMessage());
                    return;
                }
                args = new Object[] {event, body};
            }
            try {
                method.invoke(handler, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
