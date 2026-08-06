package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerModListTest {

    private static final String GAME_JSON =
            "{\"mainClass\":\"zombie/gameStates/MainScreenState\","
                    + "\"classpath\":[\".\",\"projectzomboid.jar\"],"
                    + "\"vmArgs\":[\"-Djava.library.path=win64/;.\",\"-Dzomboid.steam=1\"]}";

    @Test
    void parsesWorkshopItemsAndMods() {
        ServerModList.Result result =
                ServerModList.parse(
                        List.of(
                                "STORM_MODLIST_OK",
                                "serverName=",
                                "gameMap=AZSpawn;Muldraugh, KY;",
                                "maxPlayers=100",
                                "workshop=3670772371",
                                "workshop=2335368829",
                                "mod=storm-core-b42",
                                "mod=Authentic Z - Current"));
        assertNotNull(result);
        assertEquals("AZSpawn;Muldraugh, KY;", result.gameMap);
        assertEquals(100, result.maxPlayers);
        assertEquals(List.of("3670772371", "2335368829"), result.workshopItems);
        assertEquals(List.of("storm-core-b42", "Authentic Z - Current"), result.mods);
    }

    @Test
    void withoutTheMarkerNothingIsTrusted() {
        // A crashed child that printed part of a list must not read as "no mods required".
        assertNull(ServerModList.parse(List.of("workshop=3670772371", "mod=storm-core-b42")));
        assertNull(ServerModList.parse(List.of()));
    }

    @Test
    void malformedWorkshopIdsAreDropped() {
        ServerModList.Result result =
                ServerModList.parse(
                        List.of(
                                "STORM_MODLIST_OK",
                                "workshop=3670772371",
                                "workshop=../../etc/passwd",
                                "workshop=12345678901234567890123",
                                "workshop=",
                                "noise without an equals sign",
                                "unknownKey=whatever"));
        assertNotNull(result);
        assertEquals(List.of("3670772371"), result.workshopItems);
        assertTrue(result.mods.isEmpty());
    }

    @Test
    void emptyListIsStillASuccessfulProbe() {
        // A vanilla server with no mods answers with a real, empty requirement set.
        ServerModList.Result result = ServerModList.parse(List.of("STORM_MODLIST_OK"));
        assertNotNull(result);
        assertTrue(result.workshopItems.isEmpty());
        assertTrue(result.mods.isEmpty());
    }

    /** Passing a password as an argument would publish it to every process listing on the box. */
    @Test
    void commandCarriesTheUsernameButNoPassword() {
        Path jvm = Paths.get("/games/pz/jre64/bin/java");
        List<String> command =
                ServerModList.buildCommand(
                        jvm,
                        PzGameJson.parse(GAME_JSON),
                        Paths.get("/mods/storm/42/lib"),
                        "40.160.20.9",
                        16261,
                        "Gus Puffy",
                        30_000L);

        assertEquals(jvm.toString(), command.get(0));
        assertEquals(ServerModList.CHILD_MAIN_CLASS, command.get(command.size() - 5));
        assertEquals(
                List.of("40.160.20.9", "16261", "Gus Puffy", "30000"),
                command.subList(command.size() - 4, command.size()));

        int cp = command.indexOf("-cp");
        assertTrue(cp > 0, "child must be launched with an explicit classpath");
        assertEquals(".:projectzomboid.jar:/mods/storm/42/lib/*", command.get(cp + 1));
        assertTrue(
                command.contains("-Dzomboid.steam=1"), "the probe logs in over Steam networking");
    }
}
