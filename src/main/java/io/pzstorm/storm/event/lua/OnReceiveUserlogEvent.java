package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import io.pzstorm.storm.lua.StormKahluaTable;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;

/** Triggered when game client receives user log. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnReceiveUserlogEvent implements LuaEvent {

    // TODO: finish documenting this event
    public final String identifier;
    public final ArrayList<?> userLogs;
    public final StormKahluaTable table;

    public OnReceiveUserlogEvent(
            String identifier, ArrayList<?> userLogs, @Nullable KahluaTable table) {
        this.identifier = identifier;
        this.userLogs = userLogs;
        if (table == null) {
            this.table = null;
        } else {
            this.table = new StormKahluaTable(table);
        }
    }
}
