package io.pzstorm.storm.mod;

import java.util.Collections;
import java.util.List;

/**
 * This class represents a Project Zomboid Java mod entry point. Every mod is expected to have a
 * single class that implements this class. Mods that do not implement this class will not be
 * registered and will not be eligible to subscribe to events.
 */
public interface ZomboidMod {
    void registerEventHandlers();

    /**
     * Return command classes to register with the server command system. Each class must extend
     * {@code zombie.commands.CommandBase}.
     */
    default List<Class<?>> getCommandClasses() {
        return Collections.emptyList();
    }
}
