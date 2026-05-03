package io.pzstorm.storm.diagnostics.commands;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

@ClientCommand(module = "StormDiagnostics", command = "ramAlloc")
public class RamAllocationCommand extends ClientCommandEvent {

    public RamAllocationCommand(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }

    public long getMaxMb() {
        return getDouble("maxMb").longValue();
    }

    public long getTotalMb() {
        return getDouble("totalMb").longValue();
    }

    public long getUsedMb() {
        return getDouble("usedMb").longValue();
    }

    public long getFreeMb() {
        return getDouble("freeMb").longValue();
    }
}
