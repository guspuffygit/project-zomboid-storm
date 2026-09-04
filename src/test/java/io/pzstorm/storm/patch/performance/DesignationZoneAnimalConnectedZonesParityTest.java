package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * Oracle test: the untransformed vanilla {@code DesignationZoneAnimal.getAllDZones} on the test
 * classpath is the reference; {@link DesignationZoneAnimalConnectedZones#getAllDZones} must return
 * an element-for-element identical list (same objects, same order) for every start zone of every
 * random layout, plus the in-place / null / {@code previousZone} edge semantics the callers rely
 * on.
 *
 * <p>Zones are {@code Unsafe.allocateInstance}d: the real constructor calls {@code check()}, which
 * needs a live {@code IsoWorld}. The flood-fill only reads {@code x, y, z, w, h} and the static
 * {@code designationAnimalZoneList}, so a field-poked instance is enough.
 */
class DesignationZoneAnimalConnectedZonesParityTest implements UnitTest {

    private static Unsafe unsafe;

    @BeforeAll
    static void setUpUnsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (Unsafe) f.get(null);
    }

    @BeforeEach
    @AfterEach
    void clearZones() {
        DesignationZoneAnimal.designationAnimalZoneList.clear();
        DesignationZoneAnimalConnectedZones.resetBroken();
    }

    private static DesignationZoneAnimal zone(int x, int y, int z, int w, int h) throws Exception {
        DesignationZoneAnimal zone =
                (DesignationZoneAnimal) unsafe.allocateInstance(DesignationZoneAnimal.class);
        zone.x = x;
        zone.y = y;
        zone.z = z;
        zone.w = w;
        zone.h = h;
        DesignationZoneAnimal.designationAnimalZoneList.add(zone);
        return zone;
    }

    private static void assertSameElements(
            ArrayList<DesignationZoneAnimal> expected, ArrayList<DesignationZoneAnimal> actual) {
        assertEquals(expected.size(), actual.size(), "connected-zone count");
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), actual.get(i), "element " + i);
        }
    }

    @Test
    void randomLayoutsMatchVanillaFromEveryStartZone() throws Exception {
        Random rng = new Random(20260830L);
        for (int layout = 0; layout < 60; layout++) {
            clearZones();
            int count = 1 + rng.nextInt(40);
            for (int i = 0; i < count; i++) {
                zone(
                        rng.nextInt(90) - 30,
                        rng.nextInt(90) - 30,
                        rng.nextInt(4) - 1,
                        1 + rng.nextInt(6),
                        1 + rng.nextInt(6));
            }
            for (DesignationZoneAnimal start :
                    new ArrayList<>(DesignationZoneAnimal.designationAnimalZoneList)) {
                ArrayList<DesignationZoneAnimal> expected =
                        DesignationZoneAnimal.getAllDZones(null, start, null);
                ArrayList<DesignationZoneAnimal> actual =
                        DesignationZoneAnimalConnectedZones.getAllDZones(null, start, null);
                assertNotNull(actual);
                assertSameElements(expected, actual);
            }
        }
        assertFalse(DesignationZoneAnimalConnectedZones.isBroken());
    }

    @Test
    void probesMatchVanillaGetZoneAcrossBucketsOverlapsAndLevels() throws Exception {
        Random rng = new Random(20260904L);
        for (int layout = 0; layout < 40; layout++) {
            clearZones();
            int count = 1 + rng.nextInt(60);
            for (int i = 0; i < count; i++) {
                zone(
                        rng.nextInt(200) - 100,
                        rng.nextInt(200) - 100,
                        rng.nextInt(4) - 1,
                        1 + rng.nextInt(40),
                        1 + rng.nextInt(40));
            }
            for (int probe = 0; probe < 2000; probe++) {
                int x = rng.nextInt(260) - 130;
                int y = rng.nextInt(260) - 130;
                int z = rng.nextInt(5) - 2;
                assertSame(
                        DesignationZoneAnimal.getZone(x, y, z),
                        DesignationZoneAnimalConnectedZones.lookupZone(x, y, z),
                        "probe " + x + "," + y + "," + z);
            }
        }
        assertFalse(DesignationZoneAnimalConnectedZones.isBroken());
    }

    @Test
    void overlappingZonesResolveToTheFirstInListOrder() throws Exception {
        DesignationZoneAnimal under = zone(10, 10, 0, 10, 10);
        DesignationZoneAnimal over = zone(12, 12, 0, 10, 10);
        assertSame(under, DesignationZoneAnimal.getZone(15, 15, 0));
        assertSame(under, DesignationZoneAnimalConnectedZones.lookupZone(15, 15, 0));
        assertSame(over, DesignationZoneAnimalConnectedZones.lookupZone(21, 21, 0));

        DesignationZoneAnimal.designationAnimalZoneList.remove(under);
        DesignationZoneAnimal.designationAnimalZoneList.add(under);
        assertSame(over, DesignationZoneAnimal.getZone(15, 15, 0));
        assertSame(over, DesignationZoneAnimalConnectedZones.lookupZone(15, 15, 0));
    }

    @Test
    void snapshotFollowsEveryMutationVanillaCanMake() throws Exception {
        DesignationZoneAnimal a = zone(0, 0, 0, 4, 4);
        DesignationZoneAnimal b = zone(40, 0, 0, 4, 4);
        assertNull(DesignationZoneAnimalConnectedZones.lookupZone(20, 1, 0));
        long rebuilds = DesignationZoneAnimalConnectedZones.getIndexRebuilds();
        assertSame(a, DesignationZoneAnimalConnectedZones.lookupZone(1, 1, 0));
        assertEquals(rebuilds, DesignationZoneAnimalConnectedZones.getIndexRebuilds());

        a.w = 40;
        assertSame(a, DesignationZoneAnimal.getZone(20, 1, 0));
        assertSame(a, DesignationZoneAnimalConnectedZones.lookupZone(20, 1, 0));
        assertSameElements(
                DesignationZoneAnimal.getAllDZones(null, a, null),
                DesignationZoneAnimalConnectedZones.getAllDZones(null, a, null));

        a.z = 1;
        assertNull(DesignationZoneAnimal.getZone(1, 1, 0));
        assertNull(DesignationZoneAnimalConnectedZones.lookupZone(1, 1, 0));
        assertSame(a, DesignationZoneAnimalConnectedZones.lookupZone(1, 1, 1));

        DesignationZoneAnimal.designationAnimalZoneList.remove(b);
        assertNull(DesignationZoneAnimal.getZone(41, 1, 0));
        assertNull(DesignationZoneAnimalConnectedZones.lookupZone(41, 1, 0));

        DesignationZoneAnimal c = zone(41, 1, 0, 1, 1);
        assertSame(c, DesignationZoneAnimalConnectedZones.lookupZone(41, 1, 0));

        DesignationZoneAnimal.designationAnimalZoneList.clear();
        assertNull(DesignationZoneAnimalConnectedZones.lookupZone(41, 1, 0));
        assertNull(DesignationZoneAnimalConnectedZones.lookupZone(1, 1, 1));
    }

    @Test
    void degenerateGeometryMatchesVanilla() throws Exception {
        zone(5, 5, 0, 0, 3);
        zone(9, 9, 0, 3, 0);
        zone(20, 20, 0, -2, 3);
        DesignationZoneAnimal real = zone(5, 5, 0, 2, 2);
        for (int x = 0; x < 30; x++) {
            for (int y = 0; y < 30; y++) {
                assertSame(
                        DesignationZoneAnimal.getZone(x, y, 0),
                        DesignationZoneAnimalConnectedZones.lookupZone(x, y, 0));
            }
        }
        assertSame(real, DesignationZoneAnimalConnectedZones.lookupZone(6, 6, 0));
    }

    @Test
    void longChainMatchesVanillaAndVisitsEveryLink() throws Exception {
        DesignationZoneAnimal first = zone(0, 0, 0, 1, 1);
        for (int i = 1; i < 300; i++) {
            zone(i, 0, 0, 1, 1);
        }
        DesignationZoneAnimal last = zone(300, 0, 0, 1, 1);

        ArrayList<DesignationZoneAnimal> expected =
                DesignationZoneAnimal.getAllDZones(null, last, null);
        ArrayList<DesignationZoneAnimal> actual =
                DesignationZoneAnimalConnectedZones.getAllDZones(null, last, null);
        assertEquals(301, actual.size());
        assertTrue(actual.contains(first));
        assertSameElements(expected, actual);
    }

    @Test
    void appendsInPlaceAndReturnsTheCallerList() throws Exception {
        DesignationZoneAnimal a = zone(0, 0, 0, 2, 2);
        DesignationZoneAnimal b = zone(2, 0, 0, 2, 2);
        DesignationZoneAnimal stray = zone(50, 50, 0, 1, 1);

        ArrayList<DesignationZoneAnimal> vanillaList = new ArrayList<>();
        vanillaList.add(stray);
        ArrayList<DesignationZoneAnimal> stormList = new ArrayList<>();
        stormList.add(stray);

        ArrayList<DesignationZoneAnimal> expected =
                DesignationZoneAnimal.getAllDZones(vanillaList, a, null);
        ArrayList<DesignationZoneAnimal> actual =
                DesignationZoneAnimalConnectedZones.getAllDZones(stormList, a, null);

        assertSame(stormList, actual, "IsoAnimal.connectedDZone relies on in-place population");
        assertSame(vanillaList, expected);
        assertSameElements(expected, actual);
        assertSame(stray, actual.get(0));
        assertTrue(actual.contains(a));
        assertTrue(actual.contains(b));
    }

    @Test
    void preSeededZoneIsNotDuplicated() throws Exception {
        DesignationZoneAnimal a = zone(0, 0, 0, 2, 2);
        zone(2, 0, 0, 2, 2);

        ArrayList<DesignationZoneAnimal> vanillaList = new ArrayList<>();
        vanillaList.add(a);
        ArrayList<DesignationZoneAnimal> stormList = new ArrayList<>();
        stormList.add(a);

        assertSameElements(
                DesignationZoneAnimal.getAllDZones(vanillaList, a, null),
                DesignationZoneAnimalConnectedZones.getAllDZones(stormList, a, null));
        assertEquals(2, stormList.size());
    }

    @Test
    void previousZoneIsExcludedFromTheFirstHop() throws Exception {
        DesignationZoneAnimal a = zone(0, 0, 0, 2, 2);
        DesignationZoneAnimal b = zone(2, 0, 0, 2, 2);
        zone(4, 0, 0, 2, 2);

        assertSameElements(
                DesignationZoneAnimal.getAllDZones(null, b, a),
                DesignationZoneAnimalConnectedZones.getAllDZones(null, b, a));
    }

    @Test
    void nullZoneReturnsTheSameShapeAsVanilla() throws Exception {
        ArrayList<DesignationZoneAnimal> fresh =
                DesignationZoneAnimalConnectedZones.getAllDZones(null, null, null);
        assertNotNull(fresh);
        assertTrue(fresh.isEmpty());

        ArrayList<DesignationZoneAnimal> given = new ArrayList<>();
        given.add(zone(0, 0, 0, 1, 1));
        assertSame(given, DesignationZoneAnimalConnectedZones.getAllDZones(given, null, null));
        assertEquals(1, given.size());
    }
}
