package io.pzstorm.storm.event.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.IOException;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;

/**
 * This class represents an in-game event dispatched to Lua mods. Lua events are handled by {@link
 * LuaEventManager}. Storm intercepts these events and dispatches them as {@link LuaEvent}
 * implementation instances. Subscribing to events being defined as Java classes offers much easier
 * and safer interaction to subscribers then when subscribing from Lua.
 *
 * <h3>Note for developers</h3>
 *
 * <p>When writing new event implementation classes do not forget to add the class references to
 * {@code LuaEventFactory} static block so that the references are properly mapped and recognized by
 * factory. If the references are not added the factory will not be able create new instances of
 * those event.
 */
public interface LuaEvent extends ZomboidEvent {

    @Override
    default String getName() {
        String className = getClass().getSimpleName();
        if (className.endsWith("Event")) {
            return className.substring(className.length() - 4);
        } else return className;
    }

    default void registerCallback() {
        Event event = LuaEventManager.AddEvent(getName());
        if (event.callbacks.isEmpty()) {
            try {
                LOGGER.debug("Registering callback: {}", getName());
                event.callbacks.add(LuaCompiler.loadstring("", "console", LuaManager.env));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
