package io.pzstorm.storm.event;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

@ClientCommand(module = "test", command = "doSomething")
public class StubClientCommandEvent extends ClientCommandEvent {

    public StubClientCommandEvent(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }
}
