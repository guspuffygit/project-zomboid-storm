package io.pzstorm.storm.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * {@link StormFloorFlags#compute(String)} against a literal transcription of {@code
 * IsoGridSquare.hasSand}/{@code hasDirt}/{@code hasNaturalFloor} (42.20.4, lines 11045–11095), over
 * every name shape those expressions branch on.
 */
class StormFloorFlagsTest implements UnitTest {

    private static boolean vanillaSand(String name) {
        if (!name.contains("blends_natural_01") && !name.contains("floors_exterior_natural_01")) {
            return false;
        }
        return name.equals("blends_natural_01_0")
                        || name.equals("blends_natural_01_5")
                        || name.equals("blends_natural_01_6")
                        || name.equals("blends_natural_01_7")
                ? true
                : name.contains("floors_exterior_natural_24");
    }

    private static boolean vanillaDirt(String name) {
        if (!name.contains("blends_natural_01") && !name.contains("floors_exterior_natural_01")) {
            return false;
        } else if (name.equals("blends_natural_01_64")
                || name.equals("blends_natural_01_69")
                || name.equals("blends_natural_01_70")
                || name.equals("blends_natural_01_71")) {
            return true;
        } else {
            return name.equals("blends_natural_01_80")
                            || name.equals("blends_natural_01_85")
                            || name.equals("blends_natural_01_86")
                            || name.equals("blends_natural_01_87")
                    ? true
                    : name.equals("floors_exterior_natural_16")
                            || name.equals("floors_exterior_natural_17")
                            || name.equals("floors_exterior_natural_18")
                            || name.equals("floors_exterior_natural_19");
        }
    }

    private static boolean vanillaNatural(String name) {
        return name.startsWith("blends_natural_01") || name.startsWith("floors_exterior_natural");
    }

    @Test
    void computeMatchesVanillaPredicates() {
        String[] corpus = {
            "",
            "x",
            "blends_natural_01",
            "blends_natural_01_",
            "blends_natural_01_0",
            "blends_natural_01_00",
            "blends_natural_01_5",
            "blends_natural_01_6",
            "blends_natural_01_7",
            "blends_natural_01_8",
            "blends_natural_01_64",
            "blends_natural_01_69",
            "blends_natural_01_70",
            "blends_natural_01_71",
            "blends_natural_01_72",
            "blends_natural_01_80",
            "blends_natural_01_85",
            "blends_natural_01_86",
            "blends_natural_01_87",
            "blends_natural_01_88",
            "blends_natural_02_0",
            "xblends_natural_01_0",
            "floors_exterior_natural",
            "floors_exterior_natural_01",
            "floors_exterior_natural_01_3",
            "floors_exterior_natural_16",
            "floors_exterior_natural_17",
            "floors_exterior_natural_18",
            "floors_exterior_natural_19",
            "floors_exterior_natural_20",
            "floors_exterior_natural_24",
            "floors_exterior_natural_01_24",
            "floors_exterior_natural_24_1",
            "floors_exterior_natural_016",
            "floors_exterior_natural_0116",
            "floors_exterior_street_01_0",
            "floors_interior_tilesandwood_01_1",
            "blends_street_01_0",
            "floors_exterior_natural_01floors_exterior_natural_24",
            "Blends_Natural_01_0"
        };
        for (String name : corpus) {
            int flags = StormFloorFlags.compute(name);
            assertEquals(vanillaNatural(name), (flags & StormFloorFlags.NATURAL) != 0, name);
            assertEquals(vanillaSand(name), (flags & StormFloorFlags.SAND) != 0, name);
            assertEquals(vanillaDirt(name), (flags & StormFloorFlags.DIRT) != 0, name);
        }
    }
}
