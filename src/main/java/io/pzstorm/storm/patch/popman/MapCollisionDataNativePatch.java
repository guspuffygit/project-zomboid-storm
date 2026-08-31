package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.popman.StormMapCollisionData;

/**
 * Binds the 17 natives of {@code zombie.MapCollisionData} — collision map, {@code chunkdata}
 * persistence, wall-following pathfinder, metagrid registration and game state — to {@link
 * StormMapCollisionData}. The five {@code n_setGameState} overloads share a name; ByteBuddy's
 * delegation picks the facade overload whose parameter types match each one.
 */
public class MapCollisionDataNativePatch extends NativeFacadePatch {

    static final String[] NATIVES = {
        "n_init",
        "n_chunkUpdateTask",
        "n_squareUpdateTask",
        "n_pathTask",
        "n_hasDataForThread",
        "n_shouldWait",
        "n_update",
        "n_save",
        "n_stop",
        "n_setGameState",
        "n_initMetaGrid",
        "n_initMetaCell",
        "n_initMetaChunk",
    };

    public MapCollisionDataNativePatch() {
        super("zombie.MapCollisionData", StormMapCollisionData.class, NATIVES);
    }
}
