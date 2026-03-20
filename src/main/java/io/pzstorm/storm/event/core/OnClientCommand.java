package io.pzstorm.storm.event.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation placed on handler methods that subscribe to typed client command events. Methods
 * annotated with this must have exactly one parameter that extends {@link ClientCommandEvent}. The
 * parameter type's {@link ClientCommand} annotation determines which module and command the handler
 * receives.
 *
 * @see ClientCommand
 * @see ClientCommandEvent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnClientCommand {}
