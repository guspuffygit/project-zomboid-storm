package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.entity.StormEcsMemoHolder;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import zombie.characters.ecs.ECSComponent;
import zombie.characters.ecs.ECSEntity;

/**
 * Runs the woven {@code ECSEntity.tryGetECSComponent} against a minimal holder entity and checks
 * the memo's observable contract: every distinct requested class stays resident at once, hits never
 * touch the component map, negatives are served from the memo until {@code setECSComponent} drops
 * it, and a ninth class overwrites only the last slot.
 *
 * <p>The identity-hash slot scheme this replaced passed the weave test while thrashing on ATF (scan
 * #12, 2026-09-04: three classes in one slot). Residency of all requested classes is the property
 * that scheme could not guarantee, so it is asserted directly.
 *
 * <p>{@code ECSEntity} (woven) and the rest of the {@code ecs} / {@code component} packages (raw)
 * are defined child-first so every {@code ECSComponent} descriptor links against the woven
 * interface; the test only reaches the entity through reflection and {@link StormEcsMemoHolder}.
 */
class EcsMemoBehaviorTest implements UnitTest {

    private static final String ENTITY = "zombie.characters.ecs.ECSEntity";
    private static final String COMPONENT = "zombie.characters.ecs.ECSComponent";
    private static final String FIXTURE_PREFIX = EcsMemoBehaviorTest.class.getName() + "$";

    public static final class CountingMap
            extends HashMap<Class<? extends ECSComponent>, ECSComponent> {
        public int gets;

        @Override
        public ECSComponent get(Object key) {
            gets++;
            return super.get(key);
        }
    }

    public static final class Entity implements ECSEntity, StormEcsMemoHolder {
        public final CountingMap map = new CountingMap();
        public volatile Object[] memo;

        @Override
        public HashMap<Class<? extends ECSComponent>, ECSComponent> getECSComponentMap() {
            return map;
        }

        @Override
        public Object[] getStormEcsMemo() {
            return memo;
        }

        @Override
        public void setStormEcsMemo(Object[] memo) {
            this.memo = memo;
        }
    }

    public static class C1 extends ECSComponent {}

    public static class C1Sub extends C1 {}

    public static class C2 extends ECSComponent {}

    public static class C3 extends ECSComponent {}

    public static class C4 extends ECSComponent {}

    public static class C5 extends ECSComponent {}

    public static class C6 extends ECSComponent {}

    public static class C7 extends ECSComponent {}

    public static class C8 extends ECSComponent {}

    public static class C9 extends ECSComponent {}

    private ClassLoader loader;
    private Class<?> entityClass;
    private Method tryGet;
    private Method setComponent;

    private void weave() throws Exception {
        byte[] raw = readClass(ENTITY);
        byte[] woven = new EcsEntityTryGetMemoPatch().transform(raw);
        assertNotNull(woven);
        ClassLoader parent = getClass().getClassLoader();
        loader =
                new ClassLoader(parent) {
                    private final HashMap<String, Class<?>> defined = new HashMap<>();

                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        boolean childFirst =
                                name.startsWith("zombie.characters.ecs.")
                                        || name.startsWith("zombie.characters.component.")
                                        || name.startsWith(FIXTURE_PREFIX);
                        if (!childFirst) {
                            return super.loadClass(name, resolve);
                        }
                        Class<?> c = defined.get(name);
                        if (c == null) {
                            byte[] bytes;
                            try {
                                bytes = ENTITY.equals(name) ? woven : readClass(name);
                            } catch (Exception e) {
                                throw new ClassNotFoundException(name, e);
                            }
                            c = defineClass(name, bytes, 0, bytes.length);
                            defined.put(name, c);
                        }
                        return c;
                    }
                };
        entityClass = loader.loadClass(FIXTURE_PREFIX + "Entity");
        assertNotSame(Entity.class, entityClass);
        Class<?> wovenEntity = loader.loadClass(ENTITY);
        tryGet = wovenEntity.getMethod("tryGetECSComponent", Class.class);
        setComponent = wovenEntity.getMethod("setECSComponent", loader.loadClass(COMPONENT));
    }

    private Class<?> fixture(String simple) throws Exception {
        return loader.loadClass(FIXTURE_PREFIX + simple);
    }

    private Object newEntity() throws Exception {
        return entityClass.getConstructor().newInstance();
    }

    private Object lookup(Object entity, Class<?> requested) throws Exception {
        return tryGet.invoke(entity, requested);
    }

    private int gets(Object entity) throws Exception {
        Object map = entityClass.getField("map").get(entity);
        return map.getClass().getField("gets").getInt(map);
    }

    private static Set<Object> residentClasses(Object entity) {
        Object[] memo = ((StormEcsMemoHolder) entity).getStormEcsMemo();
        assertNotNull(memo);
        Set<Object> classes = new LinkedHashSet<>();
        for (Object slot : memo) {
            if (slot != null) {
                classes.add(((Object[]) slot)[0]);
            }
        }
        return classes;
    }

    @Test
    void everyRequestedClassStaysResidentAndHitsSkipTheMap() throws Exception {
        weave();
        Object entity = newEntity();
        Class<?>[] requested = new Class<?>[7];
        Object[] components = new Object[7];
        for (int i = 0; i < 7; i++) {
            requested[i] = fixture("C" + (i + 1));
            components[i] = requested[i].getConstructor().newInstance();
            setComponent.invoke(entity, components[i]);
        }
        for (int i = 0; i < 7; i++) {
            assertSame(components[i], lookup(entity, requested[i]));
        }
        Set<Object> resident = residentClasses(entity);
        assertEquals(7, resident.size(), "all seven requested classes must be memoized at once");
        for (Class<?> c : requested) {
            assertSame(true, resident.contains(c), c.getName() + " must be resident");
        }

        int before = gets(entity);
        for (int round = 0; round < 100; round++) {
            for (int i = 0; i < 7; i++) {
                assertSame(components[i], lookup(entity, requested[i]));
            }
        }
        assertEquals(before, gets(entity), "memo hits must never probe the component map");
    }

    @Test
    void subclassRequestIsItsOwnKeyAndResolvesToTheRootComponent() throws Exception {
        weave();
        Object entity = newEntity();
        Object component = fixture("C1Sub").getConstructor().newInstance();
        setComponent.invoke(entity, component);
        Class<?> root = fixture("C1");
        Class<?> sub = fixture("C1Sub");
        assertSame(component, lookup(entity, root));
        assertSame(component, lookup(entity, sub));
        assertEquals(2, residentClasses(entity).size());
        int before = gets(entity);
        assertSame(component, lookup(entity, root));
        assertSame(component, lookup(entity, sub));
        assertEquals(before, gets(entity));
    }

    @Test
    void absentIsMemoizedUntilRegistrationDropsTheMemo() throws Exception {
        weave();
        Object entity = newEntity();
        Class<?> missing = fixture("C2");
        assertNull(lookup(entity, missing));
        int before = gets(entity);
        assertNull(lookup(entity, missing));
        assertNull(lookup(entity, missing));
        assertEquals(before, gets(entity), "a memoized absent must not probe the map");

        Object component = missing.getConstructor().newInstance();
        setComponent.invoke(entity, component);
        assertNull(((StormEcsMemoHolder) entity).getStormEcsMemo(), "registration drops the memo");
        assertSame(component, lookup(entity, missing));
    }

    @Test
    void ninthClassOverwritesOnlyTheLastSlot() throws Exception {
        weave();
        Object entity = newEntity();
        Class<?>[] requested = new Class<?>[9];
        for (int i = 0; i < 9; i++) {
            requested[i] = fixture("C" + (i + 1));
            assertNull(lookup(entity, requested[i]));
        }
        Set<Object> resident = residentClasses(entity);
        assertEquals(8, resident.size());
        for (int i = 0; i < 7; i++) {
            assertSame(true, resident.contains(requested[i]));
        }
        assertSame(false, resident.contains(requested[7]), "slot 7 was overwritten");
        assertSame(true, resident.contains(requested[8]));

        int before = gets(entity);
        for (int i = 0; i < 7; i++) {
            assertNull(lookup(entity, requested[i]));
        }
        assertEquals(before, gets(entity), "the first seven classes still hit");
    }

    private static byte[] readClass(String binaryName) throws Exception {
        try (InputStream is =
                EcsMemoBehaviorTest.class
                        .getClassLoader()
                        .getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
            assertNotNull(is, binaryName + " must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
