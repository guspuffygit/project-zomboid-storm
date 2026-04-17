package io.pzstorm.storm.transfer.commands;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

@ClientCommand(module = "StormTransfer", command = "transferItem")
public class TransferItemCommand extends ClientCommandEvent {

    public TransferItemCommand(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }

    public String getUuid() {
        return getString("uuid");
    }

    public int getItemId() {
        return getDouble("itemId").intValue();
    }

    public String getSrcContainerRef() {
        return getString("srcContainerRef");
    }

    public String getDestContainerRef() {
        return getString("destContainerRef");
    }
}
