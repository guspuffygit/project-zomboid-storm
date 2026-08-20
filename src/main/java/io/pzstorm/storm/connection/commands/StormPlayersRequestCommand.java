package io.pzstorm.storm.connection.commands;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/**
 * An admin client asking for the online players' Storm versions; answered asynchronously with a
 * {@code StormPlayers.list} server command carrying {@code { [username] = version }}.
 */
@ClientCommand(module = StormPlayersHelloCommand.MODULE, command = "request")
public class StormPlayersRequestCommand extends ClientCommandEvent {

    public StormPlayersRequestCommand(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }
}
