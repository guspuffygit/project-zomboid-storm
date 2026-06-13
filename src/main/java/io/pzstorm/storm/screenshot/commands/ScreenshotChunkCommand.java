package io.pzstorm.storm.screenshot.commands;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

@ClientCommand(module = "stormScreenshot", command = "chunk")
public class ScreenshotChunkCommand extends ClientCommandEvent {

    public ScreenshotChunkCommand(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }

    public String getId() {
        return getString("id");
    }

    public Integer getIndex() {
        Double d = getDouble("index");
        if (d == null) {
            return null;
        }
        return d.intValue();
    }

    public Integer getTotal() {
        Double d = getDouble("total");
        if (d == null) {
            return null;
        }
        return d.intValue();
    }

    public String getData() {
        return getString("data");
    }
}
