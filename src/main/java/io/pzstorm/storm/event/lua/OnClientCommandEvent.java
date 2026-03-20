package io.pzstorm.storm.event.lua;

import java.util.Optional;

import io.pzstorm.storm.event.core.LuaEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/**
 * Triggered on the server when it receives a command from a client via
 * {@code sendClientCommand(module, command, args)}.
 *
 * <p>{@code args} may be empty when the client sends a command with no arguments.
 */
@RequiredArgsConstructor
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnClientCommandEvent implements LuaEvent {

    @Getter
    private final String module;
    @Getter
    private final String command;
    @Getter
    private final IsoPlayer player;
    private final @Nullable KahluaTable args;

    public Optional<KahluaTable> getArgs() {
        return Optional.ofNullable(args);
    }
}
