package io.pzstorm.storm.event;

import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/** A ClientCommandEvent subclass missing the required @ClientCommand annotation. */
public class StubClientCommandEventNoAnnotation extends ClientCommandEvent {

    public StubClientCommandEventNoAnnotation(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }
}
