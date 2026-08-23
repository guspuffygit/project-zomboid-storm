package io.pzstorm.storm.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StormTextFilesTest {

    /** Windows-1252 "Björn" — 0xF6 is not valid UTF-8 and makes Files.readString throw. */
    private static final byte[] ANSI_MOD_INFO =
            new byte[] {
                'n',
                'a',
                'm',
                'e',
                '=',
                'B',
                'j',
                (byte) 0xF6,
                'r',
                'n',
                '\r',
                '\n',
                'i',
                'd',
                '=',
                'b',
                'j',
                'o',
                'r',
                'n'
            };

    @Test
    void shouldReadMalformedUtf8WithoutThrowing(@TempDir Path tempDir) throws IOException {
        Path modInfo = Files.write(tempDir.resolve("mod.info"), ANSI_MOD_INFO);

        Assertions.assertThrows(IOException.class, () -> Files.readString(modInfo));

        String text = StormTextFiles.read(modInfo);
        Assertions.assertTrue(text.startsWith("name=Bj"));
        Assertions.assertTrue(text.endsWith("id=bjorn"));
    }

    @Test
    void shouldSplitLinesOnEveryLineTerminator(@TempDir Path tempDir) throws IOException {
        Path ini =
                Files.write(
                        tempDir.resolve("server.ini"),
                        "Mods=a;b\r\nPublic=true\n".getBytes(StandardCharsets.UTF_8));

        List<String> lines = StormTextFiles.readLines(ini);
        Assertions.assertEquals("Mods=a;b", lines.get(0));
        Assertions.assertEquals("Public=true", lines.get(1));
    }
}
