package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.entity.StormIndexedArray;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Weaves the real {@code zombie.entity.util.Array} with {@link EntityArrayRemoveFastPathPatch},
 * loads the result, and verifies the injected index slot end-to-end: the woven class implements
 * {@link StormIndexedArray}, the {@code stormEntityArrayIndex} field is public volatile {@code
 * Object}, and the generated accessors round-trip through it. {@code AllPatchesIntegrationTest}
 * only checks the bytes resolve; this executes them — a broken {@code FieldAccessor} wiring would
 * otherwise surface only on a live server.
 *
 * <p>The advice-driven behavior (index maintenance, verdicts, self-check) is covered by {@code
 * StormEntityIndexTest} against the helper methods; this test deliberately avoids calling the woven
 * {@code add}/{@code removeValue} because their advices touch {@code GameServer}, whose static init
 * is not test-safe.
 */
class EntityArrayRemoveFastPathPatchTest implements UnitTest {

    private static final String TARGET = "zombie.entity.util.Array";

    @Test
    void wovenArrayCarriesWorkingIndexSlot() throws Exception {
        byte[] rawClass;
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream("zombie/entity/util/Array.class")) {
            assertNotNull(is, "game Array class should be on the test classpath");
            rawClass = is.readAllBytes();
        }
        byte[] transformed = new EntityArrayRemoveFastPathPatch().transform(rawClass);

        ClassLoader childFirst =
                new ClassLoader(getClass().getClassLoader()) {
                    Class<?> woven;

                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (TARGET.equals(name)) {
                            if (woven == null) {
                                woven = defineClass(name, transformed, 0, transformed.length);
                            }
                            return woven;
                        }
                        return super.loadClass(name, resolve);
                    }
                };
        Class<?> wovenArray = childFirst.loadClass(TARGET);
        assertNotSame(zombie.entity.util.Array.class, wovenArray);

        Field slot = wovenArray.getDeclaredField("stormEntityArrayIndex");
        assertEquals(Object.class, slot.getType());
        assertTrue(Modifier.isPublic(slot.getModifiers()));
        assertTrue(Modifier.isVolatile(slot.getModifiers()));

        Object instance =
                wovenArray.getDeclaredConstructor(boolean.class, int.class).newInstance(false, 4);
        assertInstanceOf(StormIndexedArray.class, instance);
        StormIndexedArray indexed = (StormIndexedArray) instance;

        assertNull(indexed.getStormEntityArrayIndex());
        Object marker = new Object();
        indexed.setStormEntityArrayIndex(marker);
        assertSame(marker, indexed.getStormEntityArrayIndex());
        assertSame(marker, slot.get(instance));
        indexed.setStormEntityArrayIndex(null);
        assertNull(indexed.getStormEntityArrayIndex());
    }
}
