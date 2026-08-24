package io.pzstorm.storm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pzstorm.storm.UnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StormJoinHandoffTest implements UnitTest {

    private static final String[] TEST_KEYS = {
        "storm.test.handoff.mods", "storm.test.handoff.flag", "storm.test.handoff.explicit",
    };

    @AfterEach
    void clearTestProperties() {
        for (String key : TEST_KEYS) {
            System.clearProperty(key);
        }
        System.clearProperty("not.storm.test.handoff");
    }

    @Test
    void promotesStormKeysWithoutClobberingExplicitProperties(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("join-handoff.properties");
        Files.writeString(
                file,
                "storm.test.handoff.mods=modA;modB;MGRS (FMCCYAYFGLE)\n"
                        + "storm.test.handoff.flag=true\n"
                        + "storm.test.handoff.explicit=from-file\n"
                        + "not.storm.test.handoff=nope\n",
                StandardCharsets.UTF_8);
        System.setProperty("storm.test.handoff.explicit", "from-command-line");

        StormJoinHandoff.apply(file.toString());

        assertEquals("modA;modB;MGRS (FMCCYAYFGLE)", System.getProperty("storm.test.handoff.mods"));
        assertEquals("true", System.getProperty("storm.test.handoff.flag"));
        assertEquals(
                "from-command-line",
                System.getProperty("storm.test.handoff.explicit"),
                "an explicit -D must win over the file");
        assertNull(
                System.getProperty("not.storm.test.handoff"),
                "only storm.* keys may be defined from a file");
    }

    @Test
    void missingOrAbsentFileIsSoft(@TempDir Path dir) {
        StormJoinHandoff.apply((String) null);
        StormJoinHandoff.apply("");
        StormJoinHandoff.apply(dir.resolve("does-not-exist.properties").toString());
        // reaching here without a throw is the assertion — every failure must be soft
    }
}
