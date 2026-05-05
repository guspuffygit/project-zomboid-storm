package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.lua.StormLuaCallFrame;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end test for {@link StormLuaCallFrame}. Drives the {@code stormteststormluacallframe}
 * server command, which builds a real {@link se.krka.kahlua.vm.LuaCallFrame} via the live game's
 * Kahlua VM, wraps it in {@link StormLuaCallFrame}, and asserts every typed accessor across number,
 * numeric-string, string, table, boolean, nil, false, and zero slots. The command emits a single
 * {@code RESULT} line summarising any failures.
 */
@ExtendWith(ServerExtension.class)
class StormLuaCallFrameLiveTest implements IntegrationTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void allAccessorsBehaveCorrectlyAgainstRealCallFrame()
            throws IOException, InterruptedException {
        String result =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormteststormluacallframe", "RESULT", COMMAND_TIMEOUT);
        Assertions.assertNotNull(
                result, "stormteststormluacallframe produced no RESULT line within timeout");
        System.out.println("[test] " + result);

        Assertions.assertFalse(
                result.contains("RESULT ERROR"),
                "stormteststormluacallframe reported an error: " + result);
        Assertions.assertTrue(
                result.contains("ok=true"),
                "StormLuaCallFrame accessors returned wrong values: " + result);
    }
}
