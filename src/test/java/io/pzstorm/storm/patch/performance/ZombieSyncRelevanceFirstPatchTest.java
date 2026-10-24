package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.characters.IsoZombie;
import zombie.characters.component.NetworkZombieComponent;
import zombie.characters.ecs.ECSComponent;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.Vector3;
import zombie.network.packets.character.ZombieSynchronizationPacket;

/**
 * {@code ZombieSyncRelevanceFirstPatch} against the real {@code NetworkZombiePacker} class bytes.
 *
 * <p>The weave half checks that both {@code getOwner()} calls in {@code getZombieData} now go
 * through {@code StormZombieSyncRelevance.ownerIfRelevant}, bracketed by {@code begin}/{@code end},
 * and that no other method changed.
 *
 * <p>The behavioral half defines the vanilla and the patched class each in a throwaway class loader
 * and runs {@code getZombieData} on both against the same zombies and connections (built with
 * {@link Unsafe#allocateInstance}, no cell or world): what is queued, in what order, what is
 * recorded in {@code zombiesToSend}, which zombies are marked, and the count returned must match
 * for every connection. A zombie far from the connection whose owner probe would throw shows that
 * the patched method no longer probes it.
 */
class ZombieSyncRelevanceFirstPatchTest implements UnitTest {

    private static final String PACKER = "zombie/popman/NetworkZombiePacker";
    private static final String HELPER = "io/pzstorm/storm/zombie/StormZombieSyncRelevance";
    private static final String GET_ZOMBIE_DATA =
            "getZombieData(Lzombie/core/raknet/UdpConnection;"
                    + "Lzombie/network/packets/character/ZombieSynchronizationPacket;)I";

    private static Unsafe unsafe;

    @BeforeEach
    void resetHelper() throws Exception {
        Method reset = Class.forName(HELPER.replace('/', '.')).getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test
    void bothOwnerProbesInGetZombieDataGoThroughTheSubstitute() throws Exception {
        byte[] raw = readClassBytes();
        byte[] transformed = new ZombieSyncRelevanceFirstPatch().transform(raw);
        assertNotNull(transformed);

        Map<String, Counts> before = countPerMethod(raw);
        Map<String, Counts> after = countPerMethod(transformed);
        Counts vanilla = before.get(GET_ZOMBIE_DATA);
        Counts patched = after.get(GET_ZOMBIE_DATA);
        assertNotNull(vanilla, "vanilla NetworkZombiePacker must declare " + GET_ZOMBIE_DATA);
        assertEquals(2, vanilla.getOwner, "vanilla getZombieData probes the owner twice");
        assertEquals(0, patched.getOwner, "every getOwner() call must be substituted");
        assertEquals(2, patched.ownerIfRelevant, "both calls go through ownerIfRelevant");
        assertEquals(1, patched.begin, "the enter advice records the connection once");
        assertTrue(patched.end >= 1, "the exit advice clears the connection");

        for (Map.Entry<String, Counts> entry : before.entrySet()) {
            if (!GET_ZOMBIE_DATA.equals(entry.getKey())) {
                assertEquals(
                        entry.getValue(),
                        after.get(entry.getKey()),
                        "method " + entry.getKey() + " must be untouched by the patch");
            }
        }
    }

    @Test
    void patchedGetZombieDataQueuesExactlyWhatVanillaQueues() throws Exception {
        byte[] raw = readClassBytes();
        byte[] transformed = new ZombieSyncRelevanceFirstPatch().transform(raw);
        Object vanilla = newPacker(loadIsolated(raw));
        Object patched = newPacker(loadIsolated(transformed));

        Random random = new Random(42);
        List<UdpConnection> connections = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UdpConnection c = connection(random.nextFloat() * 600, random.nextFloat() * 600);
            setField(UdpConnection.class, c, "relevantRange", (byte) (3 + random.nextInt(6)));
            if (i % 3 == 0) {
                // A second relevance source: the connect area square, read before releventPos.
                c.connectArea[1] = new Vector3(random.nextFloat() * 70, random.nextFloat() * 70, 4);
            }
            connections.add(c);
        }
        List<IsoZombie> zombies = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            UdpConnection owner =
                    random.nextInt(5) == 0
                            ? null
                            : connections.get(random.nextInt(connections.size()));
            IsoZombie z =
                    zombie(random.nextFloat() * 640 - 20, random.nextFloat() * 640 - 20, owner);
            z.onlineId = random.nextInt(10) == 0 ? (short) -1 : (short) i;
            zombies.add(z);
        }
        int sent = 0;
        for (UdpConnection c : connections) {
            Outcome want = run(vanilla, zombies, c);
            Outcome got = run(patched, zombies, c);
            assertEquals(want.returned, got.returned, "getZombieData's count");
            assertEquals(want.queued, got.queued, "the zombies queued, in order");
            assertEquals(want.recorded, got.recorded, "the ids recorded in zombiesToSend");
            assertEquals(want.marked, got.marked, "the zombies marked not updated");
            sent += want.returned;
        }
        assertTrue(sent > 0, "the fixture must send something or it compares nothing");
        assertTrue(sent < zombies.size() * connections.size() / 4, "and must not send everything");
        assertNull(packingConnection(), "the connection is cleared when getZombieData returns");
    }

    @Test
    void aZombieOutOfRangeIsNotProbedForItsOwner() throws Exception {
        byte[] raw = readClassBytes();
        Object vanilla = newPacker(loadIsolated(raw));
        Object patched =
                newPacker(loadIsolated(new ZombieSyncRelevanceFirstPatch().transform(raw)));
        UdpConnection c = connection(100, 100);
        setField(UdpConnection.class, c, "relevantRange", (byte) 5);
        IsoZombie far = zombie(5000, 5000, c);
        // No component map: getOwner() would throw, so only an un-probed zombie gets through.
        setField(IsoObject.class, far, "ecsComponentMap", null);
        IsoZombie near = zombie(110, 100, connection(0, 0));
        List<IsoZombie> zombies = List.of(far, near);

        InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, () -> run(vanilla, zombies, c));
        assertNotNull(thrown.getCause(), "vanilla probes it, and the broken probe throws");
        resetHelper();
        Outcome got = run(patched, zombies, c);
        assertEquals(List.of(near), got.queued, "only the zombie in range is sent");
    }

    // --- fixtures ------------------------------------------------------------------------------

    private record Outcome(
            int returned, List<Object> queued, List<Short> recorded, List<Object> marked) {}

    private static Outcome run(Object packer, List<IsoZombie> zombies, UdpConnection c)
            throws Exception {
        setField(packer.getClass(), packer, "zombiesProcessing", new ArrayList<>(zombies));
        @SuppressWarnings("unchecked")
        Map<Object, List<Short>> toSend =
                (Map<Object, List<Short>>) packer.getClass().getField("zombiesToSend").get(packer);
        toSend.clear();
        toSend.put(c, new ArrayList<>());
        for (IsoZombie z : zombies) {
            z.zombiePacketUpdated = true;
        }
        ZombieSynchronizationPacket packet = new ZombieSynchronizationPacket();
        Method m =
                packer.getClass()
                        .getMethod(
                                "getZombieData",
                                UdpConnection.class,
                                ZombieSynchronizationPacket.class);
        int returned = (Integer) m.invoke(packer, c, packet);
        List<Object> marked = new ArrayList<>();
        for (IsoZombie z : zombies) {
            if (!z.zombiePacketUpdated) {
                marked.add(z);
            }
        }
        return new Outcome(returned, new ArrayList<>(packet.sendQueue), toSend.get(c), marked);
    }

    private static UdpConnection connection(float x, float y) throws Exception {
        UdpConnection c = (UdpConnection) unsafe().allocateInstance(UdpConnection.class);
        c.connectArea = new Vector3[4];
        c.releventPos = new Vector3[4];
        c.releventPos[0] = new Vector3(x, y, 0);
        return c;
    }

    private static IsoZombie zombie(float x, float y, UdpConnection owner) throws Exception {
        IsoZombie z = (IsoZombie) unsafe().allocateInstance(IsoZombie.class);
        setField(IsoMovingObject.class, z, "x", x);
        setField(IsoMovingObject.class, z, "y", y);
        HashMap<Class<? extends ECSComponent>, ECSComponent> components = new HashMap<>();
        setField(IsoObject.class, z, "ecsComponentMap", components);
        NetworkZombieComponent network =
                (NetworkZombieComponent) unsafe().allocateInstance(NetworkZombieComponent.class);
        network.setAuthOwner(owner);
        components.put(ECSComponent.getECSClass(NetworkZombieComponent.class), network);
        return z;
    }

    private static Object newPacker(Class<?> packerClass) throws Exception {
        return packerClass.getMethod("getInstance").invoke(null);
    }

    private static Object packingConnection() throws Exception {
        Field f = Class.forName(HELPER.replace('/', '.')).getDeclaredField("packing");
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        if (unsafe == null) {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        }
        return unsafe;
    }

    private byte[] readClassBytes() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(PACKER + ".class")) {
            assertNotNull(is, "NetworkZombiePacker.class must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** The packer and its nested classes from {@code classBytes}, everything else shared. */
    private Class<?> loadIsolated(byte[] classBytes) {
        String binaryName = PACKER.replace('/', '.');
        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.equals(binaryName) && !name.startsWith(binaryName + "$")) {
                            return super.loadClass(name, resolve);
                        }
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded != null) {
                            return loaded;
                        }
                        if (name.equals(binaryName)) {
                            return defineClass(name, classBytes, 0, classBytes.length);
                        }
                        String resource = name.replace('.', '/') + ".class";
                        try (InputStream is = getParent().getResourceAsStream(resource)) {
                            if (is == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] bytes = is.readAllBytes();
                            return defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                };
        try {
            return Class.forName(binaryName, true, loader);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, Counts> countPerMethod(byte[] classBytes) {
        Map<String, Counts> counts = new HashMap<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                Counts method =
                                        counts.computeIfAbsent(
                                                name + descriptor, k -> new Counts());
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if ("zombie/characters/IsoZombie".equals(owner)
                                                && "getOwner".equals(mname)) {
                                            method.getOwner++;
                                        }
                                        if (HELPER.equals(owner)) {
                                            switch (mname) {
                                                case "ownerIfRelevant" -> method.ownerIfRelevant++;
                                                case "begin" -> method.begin++;
                                                case "end" -> method.end++;
                                                default -> {}
                                            }
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return counts;
    }

    private static final class Counts {
        int getOwner;
        int ownerIfRelevant;
        int begin;
        int end;

        @Override
        public boolean equals(Object o) {
            return o instanceof Counts other
                    && getOwner == other.getOwner
                    && ownerIfRelevant == other.ownerIfRelevant
                    && begin == other.begin
                    && end == other.end;
        }

        @Override
        public int hashCode() {
            return ((getOwner * 31 + ownerIfRelevant) * 31 + begin) * 31 + end;
        }

        @Override
        public String toString() {
            return "getOwner="
                    + getOwner
                    + " ownerIfRelevant="
                    + ownerIfRelevant
                    + " begin="
                    + begin
                    + " end="
                    + end;
        }
    }
}
