package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the stage vocabulary shared by {@link ConnectionStage#classify} and {@code
 * StormConnectionStageMetrics}.
 *
 * <p>The metrics class pre-resolves one datapoint per entry of {@link ConnectionStage#ALL} and
 * looks stages up by {@link ConnectionStage#indexOf}, so a constant that exists but is missing from
 * {@code ALL} would silently drop those connections out of {@code storm_connections} — the
 * per-stage counts would stop summing to {@code storm_connection_slots_used} with no error
 * anywhere.
 *
 * <p>Behavioural classification is covered by {@code ConnectionStageMetricsLiveTest}, which drives
 * a real login: {@code UdpConnection}'s constructor instantiates every packet handler in {@code
 * PacketsCache}, so building one in a unit test is not worth the static-init surface.
 */
class ConnectionStageTest implements UnitTest {

    @Test
    void everyStageConstantIsListedInAll() throws Exception {
        List<String> constants = new ArrayList<>();
        for (Field field : ConnectionStage.class.getDeclaredFields()) {
            if (field.getType() != String.class || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            constants.add((String) field.get(null));
        }
        assertEquals(
                ConnectionStage.ALL.length,
                constants.size(),
                "ConnectionStage.ALL must list every public stage constant: " + constants);
        for (String constant : constants) {
            assertTrue(
                    ConnectionStage.indexOf(constant) >= 0,
                    constant + " is a stage constant but is missing from ConnectionStage.ALL");
        }
    }

    @Test
    void stageNamesAreUniqueAndPrometheusSafe() {
        Set<String> seen = new HashSet<>();
        for (String stage : ConnectionStage.ALL) {
            assertTrue(seen.add(stage), "duplicate stage name: " + stage);
            assertTrue(
                    stage.matches("[a-z][a-z0-9_]*"),
                    stage + " must be snake_case to be a usable label value");
        }
    }

    @Test
    void indexOfRoundTripsAndRejectsUnknownStages() {
        for (int i = 0; i < ConnectionStage.ALL.length; i++) {
            assertEquals(i, ConnectionStage.indexOf(ConnectionStage.ALL[i]));
        }
        assertEquals(-1, ConnectionStage.indexOf("not_a_stage"));
    }

    @Test
    void fullyConnectedIsTheLastStage() {
        // The metrics help text and PromQL funnel queries treat ALL as pipeline order, so the
        // terminal stage has to stay terminal.
        assertEquals(
                ConnectionStage.FULLY_CONNECTED,
                ConnectionStage.ALL[ConnectionStage.ALL.length - 1]);
        assertEquals(ConnectionStage.HANDSHAKE, ConnectionStage.ALL[0]);
    }
}
