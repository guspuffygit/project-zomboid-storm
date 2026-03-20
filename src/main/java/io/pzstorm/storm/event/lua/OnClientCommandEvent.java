package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/**
 * Triggered on the server when it receives a command from a client via
 * {@code sendClientCommand(module, command, args)}.
 *
 * <p>{@code args} may be {@code null} when the client sends a command with no arguments.
 * Event handlers should null-check before accessing it.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnClientCommandEvent implements LuaEvent {

    public final String module;
    public final String command;
    public final IsoPlayer player;
    public final @Nullable KahluaTable args;

    public OnClientCommandEvent(String module, String command, IsoPlayer player, @Nullable KahluaTable args) {
        this.module = module;
        this.command = command;
        this.player = player;
        this.args = args;
    }
}
