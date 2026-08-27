package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The live loop reads game statics that only exist in a running client, so tests cover the pure
 * pieces: splitting the comparer's error into reason/relPath/absPath, and the append-once notice.
 */
class StormChecksumKickNoticeTest {

    private static final String MISMATCH =
            "File doesn't match the one on the server:\n"
                    + "media/lua/client/vro_partlists.lua\n"
                    + "C:\\Steam\\steamapps\\workshop\\content\\108600\\2757712197\\mods\\VRO"
                    + "\\common\\media\\lua\\client\\vro_partlists.lua";

    private static final String GAME_MISMATCH =
            "File doesn't match the one on the server:\n"
                    + "media/lua/shared/timedactions/isreadabook.lua\n"
                    + "O:\\SteamLibrary\\steamapps\\common\\ProjectZomboid\\media\\lua"
                    + "\\shared\\TimedActions\\ISReadABook.lua";

    @Test
    void parseSplitsReasonRelPathAndAbsPath() {
        StormChecksumKickNotice.Parsed parsed = StormChecksumKickNotice.parse(MISMATCH);

        assertEquals("File doesn't match the one on the server", parsed.reason);
        assertEquals("media/lua/client/vro_partlists.lua", parsed.relPath);
        assertTrue(parsed.absPath.endsWith("vro_partlists.lua"));
    }

    @Test
    void parseToleratesMissingAbsPath() {
        StormChecksumKickNotice.Parsed parsed =
                StormChecksumKickNotice.parse(
                        "File doesn't exist on the client:\nmedia/lua/shared/thing.lua");

        assertEquals("media/lua/shared/thing.lua", parsed.relPath);
        assertEquals("", parsed.absPath);
    }

    @Test
    void parseRejectsProtocolErrorsAndNull() {
        assertNull(
                StormChecksumKickNotice.parse(
                        "NetChecksum: received PacketTotalChecksum in state Init"));
        assertNull(StormChecksumKickNotice.parse(null));
    }

    @Test
    void noticeAppendsBelowTheVanillaErrorExactlyOnce() {
        String once = StormChecksumKickNotice.withNotice(MISMATCH, true, false);

        assertTrue(once.startsWith(MISMATCH), "vanilla error must stay on top");
        assertTrue(once.contains("Storm has recorded which one."));
        assertSame(once, StormChecksumKickNotice.withNotice(once, true, false));
    }

    @Test
    void noticeWithoutARecordedHandoffPromisesNothing() {
        String notice = StormChecksumKickNotice.withNotice(MISMATCH, false, false);

        assertTrue(notice.contains("unsubscribe and resubscribe"));
        assertEquals(-1, notice.indexOf("Storm has recorded"));
    }

    @Test
    void baseGameFileBlamesTheBuildMismatchInsteadOfAMod() {
        String notice = StormChecksumKickNotice.withNotice(GAME_MISMATCH, true, true);

        assertTrue(notice.startsWith(GAME_MISMATCH), "vanilla error must stay on top");
        assertTrue(notice.contains("belongs to Project Zomboid itself"));
        assertEquals(-1, notice.indexOf("mods is out of date"), "no mod owns this file");
        assertSame(notice, StormChecksumKickNotice.withNotice(notice, true, true));
    }

    @Test
    void gameMediaIsRecognisedRegardlessOfSeparatorsAndCase() {
        assertTrue(
                StormChecksumKickNotice.insideGameMedia(
                        "O:\\SteamLibrary\\steamapps\\common\\ProjectZomboid\\media\\lua\\shared"
                                + "\\TimedActions\\ISReadABook.lua",
                        "O:\\SteamLibrary\\steamapps\\common\\projectzomboid\\Media"));
        assertTrue(
                StormChecksumKickNotice.insideGameMedia(
                        "/home/p/pz/media/lua/shared/thing.lua", "/home/p/pz/media"));
    }

    @Test
    void workshopContentAndUnknownRootsKeepTheModAdvice() {
        assertFalse(
                StormChecksumKickNotice.insideGameMedia(
                        "C:\\Steam\\steamapps\\workshop\\content\\108600\\2757712197\\mods\\VRO"
                                + "\\common\\media\\lua\\client\\vro_partlists.lua",
                        "C:\\Steam\\steamapps\\common\\ProjectZomboid\\media"));
        assertFalse(
                StormChecksumKickNotice.insideGameMedia(
                        "C:\\Steam\\steamapps\\common\\ProjectZomboidBeta\\media\\lua\\x.lua",
                        "C:\\Steam\\steamapps\\common\\ProjectZomboid\\media"));
        assertFalse(StormChecksumKickNotice.insideGameMedia("C:\\any\\file.lua", ""));
        assertFalse(StormChecksumKickNotice.insideGameMedia("", "C:\\pz\\media"));
    }
}
