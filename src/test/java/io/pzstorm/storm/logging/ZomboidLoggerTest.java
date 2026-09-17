package io.pzstorm.storm.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import zombie.debug.LogSeverity;

class ZomboidLoggerTest implements UnitTest {

    @Test
    void listenersSeeEveryLineAndAThrowingListenerDoesNotBlockOthers() {
        List<String> seen = new ArrayList<>();
        ZomboidLogger.addListener(
                (severity, line) -> {
                    throw new IllegalStateException("boom");
                });
        ZomboidLogger.addListener((severity, line) -> seen.add(severity + ":" + line));

        ZomboidLogger.log(LogSeverity.Error, "first");
        ZomboidLogger.log(null, "second");

        assertEquals(List.of("Error:first", "null:second"), seen);
    }
}
