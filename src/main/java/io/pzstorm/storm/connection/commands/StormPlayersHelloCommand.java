package io.pzstorm.storm.connection.commands;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/** A Storm client announcing its Storm version (sent by StormPlayersScoreboard.lua on spawn). */
@ClientCommand(module = StormPlayersHelloCommand.MODULE, command = "hello")
public class StormPlayersHelloCommand extends ClientCommandEvent {

    public static final String MODULE = "StormPlayers";

    public StormPlayersHelloCommand(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }

    public @Nullable String getVersion() {
        Object value = rawget("version");
        return value instanceof String s ? s : null;
    }
}
