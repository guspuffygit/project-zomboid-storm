package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LogScrubberTest {

    @Test
    void windowsUserDirEitherSlash() {
        assertEquals(
                "C:\\Users\\<user>\\Zomboid\\Logs\\main.log",
                LogScrubber.scrub("C:\\Users\\username\\Zomboid\\Logs\\main.log", "nobody"));
        assertEquals(
                "C:/Users/<user>/Zomboid/console.txt",
                LogScrubber.scrub("C:/Users/username/Zomboid/console.txt", "nobody"));
        assertEquals(
                "c:\\users\\<user>\\AppData",
                LogScrubber.scrub("c:\\users\\username\\AppData", "nobody"));
    }

    @Test
    void windowsUserNameWithSpaces() {
        assertEquals(
                "C:\\Users\\<user>\\Zomboid",
                LogScrubber.scrub("C:\\Users\\John Smith\\Zomboid", "nobody"));
        assertEquals(
                "Game directory: C:\\Users\\<user>",
                LogScrubber.scrub("Game directory: C:\\Users\\John Smith", "nobody"));
    }

    @Test
    void macAndLinuxHomeDirs() {
        assertEquals(
                "/Users/<user>/Zomboid/Logs", LogScrubber.scrub("/Users/gus/Zomboid/Logs", "x"));
        assertEquals("/home/<user>/.local/share", LogScrubber.scrub("/home/gus/.local/share", "x"));
        assertEquals(
                "/mnt/c/Users/<user>/Zomboid", LogScrubber.scrub("/mnt/c/Users/gus/Zomboid", "x"));
    }

    @Test
    void pathSegmentStopsAtDelimiters() {
        assertEquals(
                "path=\"C:\\Users\\<user>\" next",
                LogScrubber.scrub("path=\"C:\\Users\\bob\" next", "x"));
        assertEquals("at /home/<user>, then", LogScrubber.scrub("at /home/bob, then", "x"));
        assertEquals("(/Users/<user>)", LogScrubber.scrub("(/Users/bob)", "x"));
    }

    @Test
    void hsErrEnvironmentLines() {
        String dump =
                "USERNAME=username\nUSER=username\nLOGNAME=username\nHOME=/home/username\n"
                        + "USERPROFILE=C:\\Users\\username\nPATH=/usr/bin\n";
        assertEquals(
                "USERNAME=<user>\nUSER=<user>\nLOGNAME=<user>\nHOME=/home/<user>\n"
                        + "USERPROFILE=C:\\Users\\<user>\nPATH=/usr/bin\n",
                LogScrubber.scrub(dump, "nobody"));
    }

    @Test
    void bareAccountNameOnWordBoundariesOnly() {
        assertEquals(
                "logged in as <user> (username2 is someone else, so is xusername)",
                LogScrubber.scrub(
                        "logged in as username (username2 is someone else, so is xusername)",
                        "username"));
        assertEquals("user=<user>;", LogScrubber.scrub("user=w.c-ouch;", "w.c-ouch"));
    }

    @Test
    void shortAccountNamesAreNotRedactedBare() {
        assertEquals("go to the game", LogScrubber.scrub("go to the game", "go"));
        assertEquals("/home/<user>/x", LogScrubber.scrub("/home/go/x", "go"));
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertEquals(null, LogScrubber.scrub(null, "x"));
        assertEquals("", LogScrubber.scrub("", "x"));
        assertEquals("plain", LogScrubber.scrub("plain", null));
    }

    @Test
    void bytesRoundTripUtf8() {
        byte[] in = "C:\\Users\\username\\ünïcode".getBytes(StandardCharsets.UTF_8);
        String out = new String(LogScrubber.scrub(in), StandardCharsets.UTF_8);
        assertEquals("C:\\Users\\<user>\\ünïcode", out);
        assertFalse(out.contains("username"));
    }
}
