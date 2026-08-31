package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.popman.StormPopMan;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the entire {@code PZPopMan64} JNI surface of {@code ZombiePopulationManager} with the
 * pure-Java {@link StormPopMan}, and removes the {@code System.loadLibrary} call from the static
 * {@code init()} so the DLL is never mapped. With {@link MapCollisionDataNativePatch} covering the
 * other 17 exports, no native symbol is left for the library to serve.
 */
public class ZombiePopulationManagerNativePatch extends NativeFacadePatch {

    static final String[] NATIVES = {
        "n_init",
        "n_config",
        "n_configFloat",
        "n_configInt",
        "n_setSpawnOrigins",
        "n_setOutfitNames",
        "n_updateMain",
        "n_hasDataForThread",
        "n_readyToPause",
        "n_updateThread",
        "n_shouldWait",
        "n_beginSaveRealZombies",
        "n_saveRealZombies",
        "n_save",
        "n_saveCell",
        "n_stop",
        "n_addZombie",
        "n_aggroTarget",
        "n_loadChunk",
        "n_loadedAreas",
        "n_realZombieCount",
        "n_spawnHorde",
        "n_worldSound",
        "n_getAddZombieCount",
        "n_getAddZombieData",
        "n_hasRadarData",
        "n_requestRadarData",
        "n_getRadarZombieData",
    };

    public ZombiePopulationManagerNativePatch() {
        super("zombie.popman.ZombiePopulationManager", StormPopMan.class, NATIVES);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return super.dynamicType(locator, typePool, builder)
                .visit(
                        MemberSubstitution.relaxed()
                                .method(ElementMatchers.named("loadLibrary"))
                                .stub()
                                .on(ElementMatchers.named("init").and(ElementMatchers.isStatic())));
    }
}
