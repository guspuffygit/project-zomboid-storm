package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class MPDebugInfoNativePatchTest implements UnitTest {

    private static final String TARGET = "zombie/popman/MPDebugInfo.class";
    private static final String FACADE = "io/pzstorm/storm/popman/StormMPDebugInfo";

    @Test
    void patchListMatchesTheGameClass() throws Exception {
        NativeFacadeWeave.assertCoversDeclaredNatives(TARGET, MPDebugInfoNativePatch.NATIVES);
    }

    @Test
    void everyNativeForwardsToTheJavaFacade() throws Exception {
        NativeFacadeWeave.assertEveryNativeForwards(new MPDebugInfoNativePatch(), TARGET, FACADE);
    }
}
