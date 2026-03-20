package io.pzstorm.storm.event.core;

import io.pzstorm.storm.lua.StormKahluaTable;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;

/**
 * Abstract base class for typed client command events. Extends {@link StormKahluaTable} so that
 * subclasses have direct access to all KahluaTable operations on the command's args, and implements
 * {@link ZomboidEvent} so they can participate in the Storm event system.
 *
 * <p>Subclasses must be annotated with {@link ClientCommand} to specify the module and command they
 * represent, and must have a public constructor with signature {@code (IsoPlayer, KahluaTable)}.
 *
 * <p>When args is {@code null} (client sent command with no arguments), an empty table is used so
 * that handlers never need to null-check.
 */
public abstract class ClientCommandEvent extends StormKahluaTable implements ZomboidEvent {

    @Getter private final IsoPlayer player;

    protected ClientCommandEvent(IsoPlayer player, @Nullable KahluaTable args) {
        super(args != null ? args : LuaManager.platform.newTable());
        this.player = player;
    }

    @Override
    public String getName() {
        ClientCommand annotation = getClass().getAnnotation(ClientCommand.class);
        if (annotation != null) {
            return "ClientCommand:" + annotation.module() + ":" + annotation.command();
        }
        return getClass().getSimpleName();
    }
}
