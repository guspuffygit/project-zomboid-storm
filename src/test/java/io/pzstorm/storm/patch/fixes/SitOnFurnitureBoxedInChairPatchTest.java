package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Guards the matcher: {@code pathToSitOnFurnitureNoSpriteGrid} is private, so a rename in a game
 * update would leave the patch "successfully applied" while weaving nothing.
 */
class SitOnFurnitureBoxedInChairPatchTest {

    @Test
    void adviceIsWovenIntoPathFindBehavior2() throws Exception {
        byte[] rawClass;
        try (InputStream is =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("zombie/pathfind/PathFindBehavior2.class")) {
            assertNotNull(is, "PathFindBehavior2 should be on the test classpath");
            rawClass = is.readAllBytes();
        }
        byte[] transformed = new SitOnFurnitureBoxedInChairPatch().transform(rawClass);
        assertTrue(
                new String(transformed, StandardCharsets.ISO_8859_1)
                        .contains("addFallbackApproaches"),
                "advice call site should be present in the transformed bytecode");
        assertFalse(
                new String(rawClass, StandardCharsets.ISO_8859_1).contains("addFallbackApproaches"),
                "sanity: vanilla bytecode should not already contain the advice call site");
    }
}
