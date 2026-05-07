package io.pzstorm.storm.advice.isocellobjectlist;

import io.pzstorm.storm.iso.IsoCellObjectListGuard;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

/**
 * Wraps the {@code Set} returned by {@code IsoCell.getObjectList()} in an unmodifiable view when
 * the server is inside {@code ProcessObjects} (phase 4) so any direct mutation surfaces as an
 * {@link UnsupportedOperationException} instead of silently breaking iterator state.
 *
 * <p>See {@link IsoCellObjectListGuard} for the contract and rationale.
 */
public class IsoCellGetObjectListAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This IsoCell cell,
            @Advice.Return(readOnly = false) Set<IsoMovingObject> result) {
        result = IsoCellObjectListGuard.guard(GameServer.server, cell.isSafeToAdd(), result);
    }
}
