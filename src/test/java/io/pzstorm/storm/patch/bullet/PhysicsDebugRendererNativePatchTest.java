package io.pzstorm.storm.patch.bullet;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.patch.popman.NativeFacadeWeave;
import org.junit.jupiter.api.Test;

class PhysicsDebugRendererNativePatchTest implements UnitTest {

    private static final String TARGET = "zombie/core/physics/PhysicsDebugRenderer.class";
    private static final String FACADE = "io/pzstorm/storm/bullet/StormPhysicsDebugRenderer";

    @Test
    void patchListMatchesTheGameClass() throws Exception {
        NativeFacadeWeave.assertCoversDeclaredNatives(
                TARGET, PhysicsDebugRendererNativePatch.NATIVES);
    }

    @Test
    void everyNativeForwardsToTheJavaFacade() throws Exception {
        NativeFacadeWeave.assertEveryNativeForwards(
                new PhysicsDebugRendererNativePatch(), TARGET, FACADE);
    }

    @Test
    void facadeSignaturesDoNotNameTheGameClass() throws Exception {
        NativeFacadeWeave.assertFacadeSignaturesDoNotNameTarget(TARGET, FACADE);
    }
}
