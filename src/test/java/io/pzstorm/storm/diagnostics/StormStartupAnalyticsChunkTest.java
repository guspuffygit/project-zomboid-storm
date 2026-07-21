package io.pzstorm.storm.diagnostics;

import io.pzstorm.storm.UnitTest;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormStartupAnalyticsChunkTest implements UnitTest {

    private static final int DISCORD_HARD_LIMIT = 2000;

    @Test
    void shouldReturnSectionWhenUnderBudget() {
        String section = "**Storm settings**\n```\nVersion : 1.2.3\n```";
        List<String> chunks = StormStartupAnalytics.chunkForDiscord(section);
        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals(section, chunks.get(0));
    }

    @Test
    void shouldSplitOversizedSemicolonListWithinDiscordLimit() {
        String workshop =
                IntStream.range(0, 400)
                        .mapToObj(i -> String.format("%010d", i))
                        .collect(Collectors.joining(";"));
        String section =
                "**Server config**\n```\nMods = shortlist\nWorkshopItems = " + workshop + "\n```";
        Assertions.assertTrue(
                section.length() > DISCORD_HARD_LIMIT,
                "test input must be larger than Discord's limit to exercise the split");

        List<String> chunks = StormStartupAnalytics.chunkForDiscord(section);

        Assertions.assertTrue(chunks.size() > 1, "expected multiple chunks");
        for (String chunk : chunks) {
            Assertions.assertTrue(
                    chunk.length() <= DISCORD_HARD_LIMIT,
                    "chunk exceeded Discord limit: " + chunk.length());
        }
        long opens = chunks.stream().filter(c -> c.startsWith("```")).count();
        long closes = chunks.stream().filter(c -> c.endsWith("```")).count();
        Assertions.assertTrue(opens >= 2, "middle chunks should reopen the fence");
        Assertions.assertTrue(closes >= 2, "middle chunks should close the fence");
    }

    @Test
    void shouldSplitPreferringSemicolonBoundaries() {
        String line = "WorkshopItems = " + "aaaaa;".repeat(500);
        List<String> pieces = StormStartupAnalytics.splitLongLine(line, 200);

        Assertions.assertTrue(pieces.size() > 1);
        for (int i = 0; i < pieces.size() - 1; i++) {
            Assertions.assertTrue(
                    pieces.get(i).endsWith(";"),
                    "non-final piece should end at a semicolon boundary: " + pieces.get(i));
        }
        Assertions.assertEquals(line, String.join("", pieces));
    }

    @Test
    void shouldHardSplitWhenNoSemicolonAvailable() {
        String line = "x".repeat(5000);
        List<String> pieces = StormStartupAnalytics.splitLongLine(line, 1000);

        Assertions.assertEquals(5, pieces.size());
        for (String piece : pieces) {
            Assertions.assertEquals(1000, piece.length());
        }
        Assertions.assertEquals(line, String.join("", pieces));
    }
}
