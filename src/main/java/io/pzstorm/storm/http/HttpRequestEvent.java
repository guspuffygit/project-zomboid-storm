package io.pzstorm.storm.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps a single HTTP request served by Storm's shared HTTP server. Handlers annotated with {@link
 * HttpEndpoint} receive one of these and must write the response before returning. If a handler
 * returns without writing, the dispatcher sends an empty 204.
 *
 * <p>The wrapper hides {@link HttpExchange} so the public surface stays stable if the underlying
 * implementation changes.
 */
public class HttpRequestEvent {

    private final HttpExchange exchange;
    private boolean responseSent;

    public HttpRequestEvent(HttpExchange exchange) {
        this.exchange = exchange;
    }

    public String getMethod() {
        return exchange.getRequestMethod();
    }

    public String getPath() {
        return exchange.getRequestURI().getPath();
    }

    public URI getUri() {
        return exchange.getRequestURI();
    }

    /**
     * Parse the query string into a map. Later values overwrite earlier ones for repeated keys.
     * Returns an empty map if there is no query string.
     */
    public Map<String, String> getQueryParams() {
        Map<String, String> params = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(
                    java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    public Map<String, String> getRequestHeaders() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e :
                exchange.getRequestHeaders().entrySet()) {
            if (!e.getValue().isEmpty()) {
                result.put(e.getKey(), e.getValue().get(0));
            }
        }
        return result;
    }

    public byte[] getRequestBody() throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return in.readAllBytes();
        }
    }

    public String getRequestBodyAsString() throws IOException {
        return new String(getRequestBody(), StandardCharsets.UTF_8);
    }

    public void setResponseHeader(String name, String value) {
        exchange.getResponseHeaders().set(name, value);
    }

    public void setContentType(String contentType) {
        setResponseHeader("Content-Type", contentType);
    }

    public void send(int status, byte[] body) throws IOException {
        responseSent = true;
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    public void send(int status, String body) throws IOException {
        send(status, body.getBytes(StandardCharsets.UTF_8));
    }

    public void sendJson(int status, String json) throws IOException {
        setContentType("application/json");
        send(status, json);
    }

    public void sendEmpty(int status) throws IOException {
        responseSent = true;
        exchange.sendResponseHeaders(status, -1);
    }

    boolean wasResponseSent() {
        return responseSent;
    }

    Headers rawResponseHeaders() {
        return exchange.getResponseHeaders();
    }
}
