package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerQueryTest {

    private static final String GAME_JSON =
            "{\"mainClass\":\"zombie/gameStates/MainScreenState\","
                    + "\"classpath\":[\".\",\"projectzomboid.jar\"],"
                    + "\"vmArgs\":[\"-Djava.library.path=win64/;.\",\"-Dzomboid.steam=1\"]}";

    @Test
    void parsesReplyLines() {
        ServerQuery.Result result =
                ServerQuery.parse(
                        Arrays.asList(
                                "STORM_QUERY_OK",
                                "stormVersion=42.20.0_2.4.2",
                                "gameVersion=42.20.0",
                                "serverName=Apocalypse Test Farm",
                                "maxPlayers=64",
                                "players=7",
                                "workshop=2392709985",
                                "workshop=3752227135",
                                "mod=TchernarusMap",
                                "mod=ATFEconomy"));

        assertEquals("42.20.0_2.4.2", result.stormVersion);
        assertEquals("42.20.0", result.gameVersion);
        assertEquals("Apocalypse Test Farm", result.serverName);
        assertEquals(64, result.maxPlayers);
        assertEquals(7, result.players);
        assertEquals(Arrays.asList("2392709985", "3752227135"), result.workshopItems);
        assertEquals(Arrays.asList("TchernarusMap", "ATFEconomy"), result.mods);
    }

    /** A crashed or silent child must never read as "this server requires no workshop items". */
    @Test
    void withoutTheOkMarkerThereIsNoResult() {
        assertNull(ServerQuery.parse(Arrays.asList("workshop=2392709985", "mod=Whatever")));
        assertNull(ServerQuery.parse(Arrays.asList()));
    }

    /** The list comes from the server, so anything that is not a Steam id is dropped. */
    @Test
    void dropsMalformedWorkshopIds() {
        ServerQuery.Result result =
                ServerQuery.parse(
                        Arrays.asList(
                                "STORM_QUERY_OK",
                                "workshop=2392709985",
                                "workshop=../../etc/passwd",
                                "workshop=",
                                "workshop=12a34"));
        assertEquals(List.of("2392709985"), result.workshopItems);
    }

    @Test
    void serverNameWithAnEqualsSignSurvives() {
        ServerQuery.Result result =
                ServerQuery.parse(Arrays.asList("STORM_QUERY_OK", "serverName=a=b=c"));
        assertEquals("a=b=c", result.serverName);
    }

    @Test
    void commandRunsTheChildOnTheGameClasspathPlusStorm() {
        Path jvm = Paths.get("/games/pz/jre64/bin/java");
        List<String> command =
                ServerQuery.buildCommand(
                        jvm,
                        PzGameJson.parse(GAME_JSON),
                        Paths.get("/mods/storm/42/lib"),
                        "play.example.org",
                        16261,
                        "spw",
                        9000L);

        assertEquals(jvm.toString(), command.get(0));
        assertTrue(command.contains("-Djava.library.path=win64/;."));

        int cp = command.indexOf("-cp");
        assertTrue(cp > 0, "child must be launched with an explicit classpath");
        assertEquals(".:projectzomboid.jar:/mods/storm/42/lib/*", command.get(cp + 1));

        List<String> tail = command.subList(command.size() - 5, command.size());
        assertEquals(
                Arrays.asList(
                        ServerQuery.CHILD_MAIN_CLASS, "play.example.org", "16261", "spw", "9000"),
                tail);
    }
}
