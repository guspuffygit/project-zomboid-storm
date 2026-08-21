package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Dispatched on the dedicated server at the very start of {@code ServerMap.QueuedSaveAll(boolean)},
 * before any world state is written: cells and vehicles ({@code SaveAll}), player DB, global
 * objects, {@code GlobalModData}, world map, etc. Fires for every save pass — the {@code
 * SaveWorldEveryMinutes} autosave, an admin-queued save, and the shutdown save.
 *
 * <p>Thread: the server main thread when {@link #isQuit()} is {@code false}; the JVM shutdown-hook
 * thread when it is {@code true} (vanilla runs the quit save from {@code ServerMap.QueuedQuit}).
 *
 * <p>Vanilla's Lua {@code OnSave} is never triggered on the dedicated server ({@code
 * GameWindow.save} is a client / single-player path), so this is the only pre-save hook available
 * server-side. Use it to flush mod-held state into {@code GlobalModData} or a mod database so it
 * lands in the same save as the world.
 *
 * <p>Handler exceptions are caught and logged by {@code StormEventDispatcher}; they never abort the
 * save.
 */
@RequiredArgsConstructor
public class OnPreSaveEvent implements ZomboidEvent {

    /**
     * {@code true} for the final save during server shutdown, {@code false} for every other pass.
     */
    @Getter private final boolean quit;

    @Override
    public String getName() {
        return "OnPreSave";
    }
}
