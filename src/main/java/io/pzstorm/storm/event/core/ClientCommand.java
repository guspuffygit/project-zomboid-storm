package io.pzstorm.storm.event.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation placed on {@link ClientCommandEvent} subclasses to specify which client command module
 * and command name the event represents. When an {@code OnClientCommandEvent} fires with a matching
 * module and command, the annotated event class is instantiated and dispatched to handlers
 * annotated with {@link OnClientCommand}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ClientCommand {

    String module();

    String command();
}
