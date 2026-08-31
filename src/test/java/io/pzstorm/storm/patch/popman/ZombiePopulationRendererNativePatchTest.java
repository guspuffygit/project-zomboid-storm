package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class ZombiePopulationRendererNativePatchTest implements UnitTest {

    private static final String TARGET = "zombie/popman/ZombiePopulationRenderer.class";
    private static final String FACADE = "io/pzstorm/storm/popman/StormZombiePopulationRenderer";

    @Test
    void patchListMatchesTheGameClass() throws Exception {
        NativeFacadeWeave.assertCoversDeclaredNatives(
                TARGET, ZombiePopulationRendererNativePatch.NATIVES);
    }

    @Test
    void everyNativeForwardsToTheJavaFacade() throws Exception {
        NativeFacadeWeave.assertEveryNativeForwards(
                new ZombiePopulationRendererNativePatch(), TARGET, FACADE);
    }
}
