package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.lua.StormKahluaTable;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end test for {@link StormKahluaTable}. Drives the {@code stormteststormkahluatable} server
 * command, which builds a real {@link se.krka.kahlua.vm.KahluaTable} via the live game's Kahlua VM
 * and exercises every accessor and {@code pcall} variant — including the wrong-type and Lua-error
 * failure paths. The command emits a single {@code RESULT} line summarising any failures.
 */
@ExtendWith(ServerExtension.class)
class StormKahluaTableLiveTest implements IntegrationTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void allAccessorsAndPcallVariantsWorkAgainstRealKahluaTable()
            throws IOException, InterruptedException {
        String result =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormteststormkahluatable", "RESULT", COMMAND_TIMEOUT);
        Assertions.assertNotNull(
                result, "stormteststormkahluatable produced no RESULT line within timeout");
        System.out.println("[test] " + result);

        Assertions.assertFalse(
                result.contains("RESULT ERROR"),
                "stormteststormkahluatable reported an error: " + result);
        Assertions.assertTrue(
                result.contains("ok=true"),
                "StormKahluaTable accessors / pcall returned wrong results: " + result);
    }
}
