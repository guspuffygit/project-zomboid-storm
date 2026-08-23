package io.pzstorm.storm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Reads game-authored text files (mod.info, server ini) the way vanilla does: UTF-8 with malformed
 * bytes replaced instead of {@link CharacterCodingException} thrown. Mod authors ship mod.info
 * files saved in the Windows ANSI code page, and PZ reads them through an {@code InputStreamReader}
 * that substitutes replacement characters, so Storm must not be stricter than the game it loads
 * into.
 */
final class StormTextFiles {

    private StormTextFiles() {}

    static String read(Path path) throws IOException {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(e);
        }
    }

    static List<String> readLines(Path path) throws IOException {
        return Arrays.asList(read(path).split("\r\n|\r|\n", -1));
    }
}
