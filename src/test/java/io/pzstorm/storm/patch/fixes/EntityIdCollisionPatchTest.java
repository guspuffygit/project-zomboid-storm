package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * {@link EntityIdCollisionPatch} on the real {@code GameEntityManager}, against the vanilla class
 * as the control: each case runs {@link EntityIdCollisionScenario} twice, in two loaders, and
 * states what both do.
 */
class EntityIdCollisionPatchTest implements UnitTest {

    private static final String SCENARIO = EntityIdCollisionScenario.class.getName();
    private static final String GUARD = EntityIdCollisionGuard.class.getName();
    private static final String MANAGER = "zombie.entity.GameEntityManager";

    @Test
    void theFloorKeepsItsEntryAndNothingStaysInTheEngine() throws Exception {
        assertEquals(
                "threw RuntimeException:Stored entity mismatch"
                        + " | crate left: K0=none K1=none crateInEngine=true floorInEngine=true"
                        + " | floor left: K0=none K1=none crateInEngine=true floorInEngine=true",
                vanilla("crateLeavesThenFloor"),
                "the control: vanilla throws, takes the floor's entry and leaks both objects");
        assertEquals(
                "crate left: K0=floor K1=none crateInEngine=false floorInEngine=true"
                        + " | floor left: K0=none K1=none crateInEngine=false floorInEngine=false",
                patched("crateLeavesThenFloor"));
    }

    @Test
    void anObjectWithNoEntryStillLeavesTheEngine() throws Exception {
        assertEquals(
                "floor left: K0=none K1=none crateInEngine=true floorInEngine=false"
                        + " | crate left: K0=none K1=none crateInEngine=true floorInEngine=false",
                vanilla("floorLeavesThenCrate"),
                "the control: vanilla silently leaves the crate in the engine");
        assertEquals(
                "floor left: K0=none K1=none crateInEngine=true floorInEngine=false"
                        + " | crate left: K0=none K1=none crateInEngine=false floorInEngine=false",
                patched("floorLeavesThenCrate"));
    }

    @Test
    void anObjectWithoutACollisionLeavesExactlyAsInVanilla() throws Exception {
        assertEquals(vanilla("crateAlone"), patched("crateAlone"));
        assertEquals(
                "before: K0=crate K1=none crateInEngine=true floorInEngine=false"
                        + " | crate left: K0=none K1=none crateInEngine=false floorInEngine=false",
                patched("crateAlone"));
    }

    @Test
    void aFailureInsideVanillaStillGivesTheOccupantItsEntryBack() throws Exception {
        assertEquals(
                "threw NullPointerException:null"
                        + " | crate failed: K0=floor K1=none crateInEngine=true floorInEngine=true",
                normalised(patched("engineGoneMidUnregister")));
    }

    @Test
    void aNeverRegisteredObjectKeepsItsUncomputedId() throws Exception {
        assertEquals(vanilla("neverRegistered"), patched("neverRegistered"));
        assertEquals(" plainId=-1", patched("neverRegistered"));
    }

    @Test
    void anObjectOffTheSquareIsLeftToVanilla() throws Exception {
        assertEquals(vanilla("offTheSquare"), patched("offTheSquare"));
    }

    @Test
    void anEarlyReturnFromVanillaLeavesNothingOfOursInTheMap() throws Exception {
        Object[] occupied = {true};
        Object[] free = {false};
        assertEquals(
                "crate call: K0=floor K1=none crateInEngine=true floorInEngine=true",
                vanilla("managerAlreadyLetGo", occupied),
                "the control: vanilla returns without touching anything");
        assertEquals(
                vanilla("managerAlreadyLetGo", occupied), patched("managerAlreadyLetGo", occupied));
        assertEquals(
                "crate call: K0=none K1=none crateInEngine=true floorInEngine=false",
                patched("managerAlreadyLetGo", free),
                "the entry written for vanilla must not outlive the call");
    }

    @Test
    void aCollisionKeepsTheComponentsOnTheObjectInsteadOfOffloadingThem() throws Exception {
        assertEquals(
                "barrel left: K0=floor K1=none crateInEngine=false floorInEngine=true"
                        + " keptFluid=true metaTag=false",
                patched("barrelLeavesOverTheFloor"),
                "a MetaEntity would need the floor's id, so the fluid saves with the object");
    }

    @Test
    void aFreeIdStillOffloadsToMeta() throws Exception {
        assertEquals(
                "barrel left: K0=other K1=none crateInEngine=false floorInEngine=false"
                        + " keptFluid=false metaTag=true",
                patched("barrelLeavesWithTheIdFree"),
                "nothing else wants the id, so the barrel keeps running while unloaded");
    }

    @Test
    void eachCaseIsCounted() throws Exception {
        String before = patched("counters");
        patched("crateLeavesThenFloor");
        String afterCollision = patched("counters");
        assertNotEquals(before, afterCollision, "a collision counts");
        assertEquals(before.split("/")[1], afterCollision.split("/")[1], "and only as a collision");
        patched("floorLeavesThenCrate");
        String afterMissing = patched("counters");
        assertEquals(afterCollision.split("/")[0], afterMissing.split("/")[0]);
        assertNotEquals(afterCollision.split("/")[1], afterMissing.split("/")[1]);
    }

    private static String normalised(String result) {
        // The JVM's helpful NPE message spells out the failing call; only the type matters here.
        return result.replaceFirst("NullPointerException:.*? \\| ", "NullPointerException:null | ");
    }

    private static String vanilla(String scenario, Object... args) throws Exception {
        return run(readManager(), scenario, args);
    }

    private static String patched(String scenario, Object... args) throws Exception {
        byte[] woven = new EntityIdCollisionPatch().transform(readManager());
        assertNotNull(woven, "the patch must apply to GameEntityManager");
        return run(woven, scenario, args);
    }

    private static String run(byte[] manager, String scenario, Object... args) throws Exception {
        Class<?> type = new OwnGameClasses(manager).loadClass(SCENARIO);
        for (var method : type.getMethods()) {
            if (method.getName().equals(scenario)) {
                return (String) method.invoke(null, args);
            }
        }
        throw new AssertionError("no scenario " + scenario);
    }

    private static byte[] readManager() throws IOException {
        return read(MANAGER);
    }

    private static byte[] read(String name) throws IOException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in =
                EntityIdCollisionPatchTest.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /**
     * Defines every game class itself, child first, so the woven manager's package-private calls
     * stay inside one runtime package. Everything else comes from the test's own loader, the
     * metrics class included, so counters survive from one run to the next.
     */
    private static final class OwnGameClasses extends ClassLoader {

        private final byte[] manager;

        OwnGameClasses(byte[] manager) {
            super(EntityIdCollisionPatchTest.class.getClassLoader());
            this.manager = manager;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("zombie.")
                    && !name.startsWith(GUARD)
                    && !name.startsWith(SCENARIO)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                try {
                    byte[] bytes = name.equals(MANAGER) ? manager : read(name);
                    if (bytes == null) {
                        throw new ClassNotFoundException(name);
                    }
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }
}
