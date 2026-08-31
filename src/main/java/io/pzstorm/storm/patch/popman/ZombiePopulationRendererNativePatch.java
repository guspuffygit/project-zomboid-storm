package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.popman.StormZombiePopulationRenderer;

/**
 * Binds {@code zombie.popman.ZombiePopulationRenderer}'s five instance natives to {@link
 * StormZombiePopulationRenderer}. These are instance methods, so the facade takes the renderer as a
 * {@code @This} parameter and the delegation still lands on a static.
 */
public class ZombiePopulationRendererNativePatch extends NativeFacadePatch {

    static final String[] NATIVES = {
        "n_render",
        "n_setWallFollowerStart",
        "n_setWallFollowerEnd",
        "n_wallFollowerMouseMove",
        "n_setDebugOption",
    };

    public ZombiePopulationRendererNativePatch() {
        super(
                "zombie.popman.ZombiePopulationRenderer",
                StormZombiePopulationRenderer.class,
                NATIVES);
    }
}
