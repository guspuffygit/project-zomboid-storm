package io.pzstorm.storm.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation placed on handler methods that serve HTTP requests on Storm's shared HTTP server.
 *
 * <p>Annotated methods must return {@code void} and accept either a single {@link HttpRequestEvent}
 * parameter, or {@code (HttpRequestEvent, BodyType)} where {@code BodyType} is any
 * Jackson-deserializable class. When a body parameter is declared, the dispatcher reads the request
 * body, deserializes it via Jackson, and rejects empty or malformed bodies with a 400 before
 * invoking the handler. Paths are matched exactly (no path parameters). The server is opt-in via
 * {@code -Dstorm.http.port=<port>}.
 *
 * <p>Handlers are discovered by {@link io.pzstorm.storm.event.core.StormEventDispatcher} when the
 * declaring class or instance is registered.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpEndpoint {

    String path();

    String method() default "GET";
}
