package io.pzstorm.storm.los.commands;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/**
 * Client → server LOS report. Sent every client tick by {@code StormClientLOS.lua} for each local
 * player, carrying the set of characters the client believes the player can/could see.
 */
@ClientCommand(module = "storm_los", command = "report")
public class LOSReportCommand extends ClientCommandEvent {

    public LOSReportCommand(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }

    public short getPlayerOnlineID() {
        Double v = getDouble("playerOnlineID");
        return v == null ? (short) -1 : v.shortValue();
    }

    public long getTick() {
        Double v = getDouble("tick");
        return v == null ? 0L : v.longValue();
    }

    public long getWallMs() {
        Double v = getDouble("wallMs");
        return v == null ? 0L : v.longValue();
    }

    public boolean isSelfSpotted() {
        Boolean v = getBoolean("selfSpotted");
        return v != null && v;
    }

    public boolean isTruncated() {
        Boolean v = getBoolean("truncated");
        return v != null && v;
    }

    public @Nullable KahluaTable getEntriesTable() {
        Object obj = rawget("entries");
        return obj instanceof KahluaTable t ? t : null;
    }
}
