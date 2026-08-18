package io.pzstorm.storm.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation placed on handler methods that serve HTTP requests on the game-port HTTP server: a TCP
 * listener on the same port number as the game's UDP port ({@code DefaultPort}, conventionally
 * 16261), started once the dedicated server is up. Clients already know this host:port from the
 * server's connection details, so endpoints here are reachable without any extra client
 * configuration.
 *
 * <p>This registry is fully separate from {@link HttpEndpoint}: nothing registered for Storm's
 * backend server ({@code -Dstorm.http.port}) is ever served on the game port, and vice versa. The
 * game port is internet-facing by definition (every server browser publishes it), so handlers must
 * treat every request as untrusted — validate inputs, never expose admin or hot-reload surfaces,
 * and keep responses cheap.
 *
 * <p>Annotated methods must return {@code void} and accept either a single {@link HttpRequestEvent}
 * parameter, or {@code (HttpRequestEvent, BodyType)} where {@code BodyType} is any
 * Jackson-deserializable class. When a body parameter is declared, the dispatcher reads the request
 * body, deserializes it via Jackson, and rejects empty or malformed bodies with a 400 before
 * invoking the handler. Paths are matched exactly (no path parameters).
 *
 * <p>Handlers are discovered by {@link io.pzstorm.storm.event.core.StormEventDispatcher} when the
 * declaring class or instance is registered.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GameHttpEndpoint {

    String path();

    String method() default "GET";
}
