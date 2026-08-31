package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class DebugCommandsNativePatchTest implements UnitTest {

    private static final String TARGET = "zombie/popman/DebugCommands.class";
    private static final String FACADE = "io/pzstorm/storm/popman/StormDebugCommands";

    @Test
    void patchListMatchesTheGameClass() throws Exception {
        NativeFacadeWeave.assertCoversDeclaredNatives(TARGET, DebugCommandsNativePatch.NATIVES);
    }

    @Test
    void everyNativeForwardsToTheJavaFacade() throws Exception {
        NativeFacadeWeave.assertEveryNativeForwards(new DebugCommandsNativePatch(), TARGET, FACADE);
    }
}
