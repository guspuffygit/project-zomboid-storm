package io.pzstorm.storm.transfer.commands;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

@ClientCommand(module = "StormTransfer", command = "cancelTransfer")
public class CancelTransferCommand extends ClientCommandEvent {

    public CancelTransferCommand(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }

    public String getUuid() {
        return getString("uuid");
    }
}
