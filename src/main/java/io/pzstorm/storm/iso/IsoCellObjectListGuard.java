package io.pzstorm.storm.iso;

import java.util.Collections;
import java.util.Set;

/**
 * Runtime guard for the {@code Set} returned by {@code IsoCell.getObjectList()}.
 *
 * <p>Phase 4 of {@code IsoCell.updateInternal()} (the {@code ProcessObjects} call) iterates {@code
 * objectList} and invokes {@code .update()} on every {@code IsoMovingObject}. While that iteration
 * is in progress, {@code safeToAdd} is {@code false} and the deferred {@code addList}/{@code
 * removeList} mechanism enforces structural stability. Any code path that bypasses the deferred
 * APIs and mutates {@code objectList} directly during phase 4 (via {@code getObjectList().add(x)}
 * or similar) breaks the safe-iteration contract that downstream parallelization (e.g. parallel
 * {@code IsoPlayer.updateLOS}) relies on.
 *
 * <p>This guard wraps the returned set in {@link Collections#unmodifiableSet(Set)} when the server
 * is in phase 4, so any rogue mutation throws {@code UnsupportedOperationException} at the
 * call-site instead of silently corrupting iterator state. Outside phase 4 (and on the client), the
 * underlying set is returned unchanged so all base-game add/remove paths in {@code
 * ReanimatedPlayers}, {@code VirtualZombieManager}, {@code NetworkPlayerAI}, etc. keep working as
 * before.
 */
public final class IsoCellObjectListGuard {

    private IsoCellObjectListGuard() {}

    public static <T> Set<T> guard(boolean isServer, boolean isSafeToAdd, Set<T> set) {
        if (isServer && !isSafeToAdd) {
            return Collections.unmodifiableSet(set);
        }
        return set;
    }
}
