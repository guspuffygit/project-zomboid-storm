package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.BulletUpcalls;
import io.pzstorm.storm.bullet.pz.jni.WorldNatives;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** WorldSimulation, ChunkMap/Chunk/ChunkLevel and jni.WorldNatives against a stub upcall target. */
class WorldSimulationTest implements UnitTest {

    private final List<int[]> levelUpcalls = new ArrayList<>();
    private final List<int[]> impulses = new ArrayList<>();

    /** Runs inside updatePhysicsForLevelIfNeeded (plays the game's ToBullet push). */
    private LevelHook hook;

    private BulletUpcalls.Target savedTarget;

    interface LevelHook {
        void run(int wx, int wy, int level);
    }

    @BeforeEach
    void setUp() {
        savedTarget = BulletUpcalls.target;
        BulletUpcalls.target =
                new BulletUpcalls.Target() {
                    @Override
                    public boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
                        levelUpcalls.add(new int[] {wx, wy, level});
                        if (hook != null) {
                            hook.run(wx, wy, level);
                        }
                        return false;
                    }

                    @Override
                    public void onVehicleConstraintImpulse(int c, int a, int b, float i) {
                        impulses.add(new int[] {c, a, b});
                    }

                    @Override
                    public void nativeLog(String a, String b, String c) {}

                    @Override
                    public String getBoneName(int ordinal) {
                        return "";
                    }

                    @Override
                    public int getBoneOrdinal(String name) {
                        return -1;
                    }
                };
        WorldSimulation.instance = null;
    }

    @AfterEach
    void tearDown() {
        WorldSimulation.instance = null;
        BulletUpcalls.target = savedTarget;
    }

    private static WorldSimulation clientWorld() {
        WorldSimulation ws = new WorldSimulation(0, 0, 10, 10, 0, 0, false);
        WorldSimulation.instance = ws;
        return ws;
    }

    /** Little-endian ToBullet stream builder. */
    private static final class Cmd {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();

        Cmd u8(int v) {
            b.write(v & 0xff);
            return this;
        }

        Cmd s16(int v) {
            b.write(v & 0xff);
            b.write((v >> 8) & 0xff);
            return this;
        }

        Cmd level(int x, int y, int min, int max, int level) {
            return u8(8).s16(x).s16(y).s16(min).s16(max).s16(level);
        }

        Cmd square(int x, int y, int... shapes) {
            u8(x).u8(y).u8(shapes.length);
            for (int s : shapes) {
                u8(s);
            }
            return this;
        }

        ByteBuffer buf() {
            byte[] a = b.toByteArray();
            ByteBuffer bb = ByteBuffer.allocateDirect(a.length + 4);
            bb.put(a);
            // GetDirectBufferAddress ignores position
            bb.position(3);
            return bb;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ToBullet

    @Test
    void toBulletParsesLevelCommandsAndSkipsOthers() {
        WorldSimulation ws = clientWorld();
        ws.activateChunkMap(0, 10, 10, 3);
        ByteBuffer buf =
                new Cmd()
                        .u8(3) // unknown command: one byte
                        .level(11, 12, -1, 2, 1)
                        .square(2, 3, 7, 1)
                        .square(4, 5)
                        .square(6, 7, 11, 12, 13, 2)
                        .u8(0xff)
                        .level(500, 500, 0, 5, 0) // no chunk: squares are consumed
                        .square(1, 1, 7)
                        .u8(0xff)
                        .level(11, 12, -1, 2, 2)
                        .square(0, 0, 1)
                        .u8(0xff)
                        .u8(0xff)
                        .buf();
        WorldNatives.ToBullet(buf);

        Chunk c = ws.getChunkForAnyPlayer(11, 12);
        assertNotNull(c);
        assertEquals(-1, c.minLevel);
        assertEquals(2, c.maxLevel);
        ChunkLevel l1 = c.getLevelData(1);
        assertTrue(l1.shapesSet);
        int i = (2 * 8 + 3) * 4;
        assertArrayEquals(new int[] {7, 1, 0, 0}, slice(l1.shapes, i));
        assertArrayEquals(new int[] {0, 0, 0, 0}, slice(l1.shapes, (4 * 8 + 5) * 4));
        assertArrayEquals(new int[] {11, 12, 13, 2}, slice(l1.shapes, (6 * 8 + 7) * 4));
        ChunkLevel l2 = c.getLevelData(2);
        assertArrayEquals(new int[] {1, 0, 0, 0}, slice(l2.shapes, 0));
        // untouched level
        assertFalse(c.getLevelData(0).shapesSet);
    }

    @Test
    void toBulletRemovesBodiesOfAnAddedLevel() {
        WorldSimulation ws = clientWorld();
        ws.activateChunkMap(0, 10, 10, 3);
        Chunk c = ws.getChunkForAnyPlayer(11, 11);
        ChunkLevel l0 = c.getLevelData(0);
        l0.setShapes(1, 1, 1, new int[] {1, 0, 0, 0});
        l0.bodiesAdded = true;
        l0.addPhysicsBodies();
        int before = ws.m_dynamicsWorld.getNumCollisionObjects();
        assertNotNull(l0.bodies[(1 * 8 + 1) * 4]);

        WorldNatives.ToBullet(new Cmd().level(11, 11, 0, 0, 0).u8(0xff).u8(0xff).buf());
        assertFalse(l0.bodiesAdded);
        assertNull(l0.bodies[(1 * 8 + 1) * 4]);
        assertEquals(before - 1, ws.m_dynamicsWorld.getNumCollisionObjects());
    }

    private static int[] slice(int[] a, int i) {
        return new int[] {a[i], a[i + 1], a[i + 2], a[i + 3]};
    }

    // ---------------------------------------------------------------------------------------------
    // chunk maps

    @Test
    void activateChunkMapCreatesAndAdoptsSharedChunks() {
        WorldSimulation ws = clientWorld();
        WorldNatives.activateChunkMap(0, 10, 10, 3);
        ChunkMap m0 = ws.m_chunkMaps[0];
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Chunk c = m0.chunks[x][y];
                assertEquals(10 + x, c.wx);
                assertEquals(10 + y, c.wy);
                assertTrue(c.referenced[0]);
            }
        }
        WorldNatives.activateChunkMap(1, 11, 11, 3);
        ChunkMap m1 = ws.m_chunkMaps[1];
        // overlap 11..12 x 11..12 is shared
        assertSame(m0.chunks[1][1], m1.chunks[0][0]);
        assertSame(m0.chunks[2][2], m1.chunks[1][1]);
        assertTrue(m1.chunks[0][0].referenced[0] && m1.chunks[0][0].referenced[1]);
        assertNotSame(m0.chunks[2][2], m1.chunks[2][2]);
        assertEquals(13, m1.chunks[2][2].wx);
        assertFalse(m1.chunks[2][2].referenced[0]);

        // getChunkForAnyPlayer returns the first map's chunk; getChunk is per player
        assertSame(m0.chunks[2][2], ws.getChunkForAnyPlayer(12, 12));
        assertSame(m1.chunks[2][2], ws.getChunk(1, 13, 13));
        assertNull(ws.getChunk(0, 13, 13));
        assertNull(ws.getChunkForAnyPlayer(20, 20));

        WorldNatives.deactivateChunkMap(1);
        assertNull(ws.m_chunkMaps[1]);
        assertFalse(m0.chunks[1][1].referenced[1]);
        assertTrue(m0.chunks[1][1].referenced[0]);
    }

    @Test
    void scrollChunkMapShiftsWindowAndReusesOtherPlayersChunks() {
        WorldSimulation ws = clientWorld();
        ws.activateChunkMap(0, 10, 10, 3);
        ws.activateChunkMap(1, 11, 11, 3);
        ChunkMap m0 = ws.m_chunkMaps[0];
        ChunkMap m1 = ws.m_chunkMaps[1];
        Chunk keep = m0.chunks[1][0];
        Chunk shared = m1.chunks[2][0]; // (13, 11)

        WorldNatives.scrollChunkMap(0, 1); // right
        assertEquals(11, m0.wx);
        assertEquals(10, m0.wy);
        assertSame(keep, m0.chunks[0][0]);
        assertSame(shared, m0.chunks[2][1]);
        assertTrue(shared.referenced[0]);
        Chunk fresh = m0.chunks[2][0];
        assertEquals(13, fresh.wx);
        assertEquals(10, fresh.wy);

        WorldNatives.scrollChunkMap(0, 2); // up
        assertEquals(9, m0.wy);
        assertEquals(9, m0.chunks[0][0].wy);
        assertEquals(11, m0.chunks[0][0].wx);
        assertSame(keep, m0.chunks[0][1]);

        WorldNatives.scrollChunkMap(0, 3); // down
        assertEquals(10, m0.wy);
        WorldNatives.scrollChunkMap(0, 0); // left
        assertEquals(10, m0.wx);
        assertEquals(10, m0.chunks[0][0].wx);
        assertEquals(10, m0.chunks[0][0].wy);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                assertEquals(10 + x, m0.chunks[x][y].wx);
                assertEquals(10 + y, m0.chunks[x][y].wy);
            }
        }

        NullPointerException e =
                assertThrows(NullPointerException.class, () -> WorldNatives.scrollChunkMap(2, 0));
        assertEquals("chunkmap is null", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> WorldNatives.scrollChunkMap(-1, 0));
        assertEquals("chunkmap is null", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> WorldNatives.scrollChunkMap(4, 0));
        assertEquals("chunkmap is null", e.getMessage());
        // unknown direction: nothing
        WorldNatives.scrollChunkMap(0, 7);
        assertEquals(10, m0.wx);
    }

    @Test
    void chunkMapNativesRequireAWorld() {
        NullPointerException e =
                assertThrows(
                        NullPointerException.class,
                        () -> WorldNatives.activateChunkMap(0, 0, 0, 3));
        assertEquals("WorldSimulation::instance == null", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> WorldNatives.deactivateChunkMap(0));
        assertEquals("WorldSimulation::instance == null", e.getMessage());
        e =
                assertThrows(
                        NullPointerException.class,
                        () -> WorldNatives.setChunkMinMaxLevel(0, 0, 0, 1));
        assertEquals("WorldSimulation::instance is null", e.getMessage());
        RuntimeException r =
                assertThrows(
                        RuntimeException.class, () -> WorldNatives.stepSimulation(0.1f, 1, 0.1f));
        assertEquals("WorldSimulation::instance is null", r.getMessage());
        r = assertThrows(RuntimeException.class, () -> WorldNatives.createServerCell(0, 0));
        assertEquals("WorldSimulation::instance is null", r.getMessage());
        assertFalse(WorldNatives.isWorldInit());
        IllegalArgumentException iae =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> WorldNatives.getCorrectedWorldSpace(-1, new float[3]));
        assertEquals("id is invalid", iae.getMessage());
        iae =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> WorldNatives.getCorrectedWorldSpace(0, new float[0]));
        assertEquals("output array is too small", iae.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // level updates

    @Test
    void levelLoadAsksJavaForShapesThenAddsAndRemovesBodies() {
        WorldSimulation ws = clientWorld();
        ws.activateChunkMap(0, 10, 10, 3);
        int base = ws.m_dynamicsWorld.getNumCollisionObjects();
        hook =
                (wx, wy, level) -> {
                    if (wx == 11 && wy == 11 && level == 0) {
                        WorldNatives.ToBullet(
                                new Cmd()
                                        .level(11, 11, 0, 0, 0)
                                        .square(3, 4, 1)
                                        .square(5, 5, 6)
                                        .u8(0xff)
                                        .u8(0xff)
                                        .buf());
                    }
                };
        ChunkMap m = ws.m_chunkMaps[0];
        // square in the middle of chunk (11, 11): the 3x3 around it (the whole map) needs level 0
        m.setNeed(11 * 8 + 4, 11 * 8 + 4, 0);
        m.load();
        assertEquals(9, levelUpcalls.size());
        assertArrayEquals(new int[] {10, 10, 0}, levelUpcalls.get(0));
        Chunk c = ws.getChunk(0, 11, 11);
        ChunkLevel l0 = c.getLevelData(0);
        assertTrue(l0.bodiesAdded);
        assertFalse(l0.need);
        assertNotNull(l0.bodies[(3 * 8 + 4) * 4]);
        assertNotNull(l0.bodies[(5 * 8 + 5) * 4]);
        assertEquals(base + 2, ws.m_dynamicsWorld.getNumCollisionObjects());
        // solid at square (91, 92): box centred in the square, level 0
        assertEquals(91.5, l0.bodies[(3 * 8 + 4) * 4].m_worldTransform.m_origin.x);
        assertEquals(92.5, l0.bodies[(3 * 8 + 4) * 4].m_worldTransform.m_origin.z);

        // not needed on the next load: bodies are removed, no upcall
        levelUpcalls.clear();
        m.load();
        assertTrue(levelUpcalls.isEmpty());
        assertFalse(l0.bodiesAdded);
        assertNull(l0.bodies[(3 * 8 + 4) * 4]);
        assertEquals(base, ws.m_dynamicsWorld.getNumCollisionObjects());
    }

    @Test
    void setChunkMinMaxLevelResizesEveryPlayersChunk() {
        WorldSimulation ws = clientWorld();
        ws.activateChunkMap(0, 10, 10, 3);
        ws.activateChunkMap(2, 11, 11, 3);
        Chunk c = ws.getChunk(0, 11, 11);
        ChunkLevel l0 = c.getLevelData(0);
        WorldNatives.setChunkMinMaxLevel(11, 11, -2, 3);
        assertEquals(-2, c.minLevel);
        assertEquals(3, c.maxLevel);
        assertSame(l0, c.getLevelData(0));
        assertEquals(-2, c.getLevelData(-2).level);
        assertNull(c.getLevelData(4));
        WorldNatives.setChunkMinMaxLevel(11, 11, 1, 1);
        assertEquals(1, c.getLevelData(1).level);
        assertNull(c.getLevelData(0));
    }

    // ---------------------------------------------------------------------------------------------
    // world init + step

    @Test
    void initWorldStepsObjectOntoStaticGeometry() {
        WorldNatives.initWorld(0, 0, 10, 10, 0, 0, false);
        assertTrue(WorldNatives.isWorldInit());
        RuntimeException dup =
                assertThrows(
                        RuntimeException.class,
                        () -> WorldNatives.initWorld(0, 0, 10, 10, 0, 0, false));
        assertEquals("WorldSimulation::instance != null", dup.getMessage());
        WorldSimulation ws = WorldSimulation.instance;
        hook =
                (wx, wy, level) -> {
                    if (wx == 11 && wy == 11 && level == 0) {
                        WorldNatives.ToBullet(
                                new Cmd()
                                        .level(11, 11, 0, 0, 0)
                                        .square(4, 4, 1)
                                        .u8(0xff)
                                        .u8(0xff)
                                        .buf());
                    }
                };
        WorldNatives.activateChunkMap(0, 10, 10, 3);
        ChunkMap m = ws.m_chunkMaps[0];
        m.setNeed(92, 92, 0);
        m.load();
        assertTrue(ws.getChunk(0, 11, 11).getLevelData(0).bodiesAdded);

        int id = WorldNatives.addPhysicsObject(92.0f, 92.0f);
        assertEquals(1, id);
        float[] out = new float[4];
        assertEquals(1, WorldNatives.getObjectPhysics(out));
        assertEquals(1.0f, out[0]);
        assertEquals(92.5f, out[1]);
        assertEquals(10.5f, out[2]);
        assertEquals(92.5f, out[3]);

        // stepSimulation reloads every chunk map: levels are kept only while something (a
        // vehicle or ragdoll in the game) marks them as needed, so mark them before each step.
        for (int i = 0; i < 240; i++) {
            m.setNeed(92, 92, 0);
            WorldNatives.stepSimulation(1.0f / 60.0f, 2, 1.0f / 60.0f);
        }
        WorldNatives.getObjectPhysics(out);
        assertEquals(92.5f, out[1], 1e-3f);
        assertEquals(92.5f, out[3], 1e-3f);
        // rests on top of the solid (not in free fall, not through it)
        assertTrue(out[2] > 1.0f && out[2] < 10.5f, "height " + out[2]);
        float rest = out[2];
        m.setNeed(92, 92, 0);
        WorldNatives.stepSimulation(1.0f / 60.0f, 2, 1.0f / 60.0f);
        WorldNatives.getObjectPhysics(out);
        assertEquals(rest, out[2], 1e-3f);

        // deterministic: a second world replays the same numbers bit for bit
        WorldNatives.destroyWorld();
        WorldSimulation.instance = null;
        levelUpcalls.clear();
        WorldNatives.initWorld(0, 0, 10, 10, 0, 0, false);
        WorldNatives.activateChunkMap(0, 10, 10, 3);
        WorldSimulation.instance.m_chunkMaps[0].setNeed(92, 92, 0);
        WorldSimulation.instance.m_chunkMaps[0].load();
        WorldNatives.addPhysicsObject(92.0f, 92.0f);
        for (int i = 0; i < 241; i++) {
            WorldSimulation.instance.m_chunkMaps[0].setNeed(92, 92, 0);
            WorldNatives.stepSimulation(1.0f / 60.0f, 2, 1.0f / 60.0f);
        }
        float[] again = new float[4];
        WorldNatives.getObjectPhysics(again);
        assertEquals(Float.floatToRawIntBits(out[2]), Float.floatToRawIntBits(again[2]));
        WorldNatives.destroyWorld();
    }

    @Test
    void stepWithoutNeedDropsLevelBodies() {
        WorldSimulation ws = clientWorld();
        ws.activateChunkMap(0, 10, 10, 3);
        hook =
                (wx, wy, level) ->
                        WorldNatives.ToBullet(
                                new Cmd()
                                        .level(wx, wy, 0, 0, 0)
                                        .square(4, 4, 1)
                                        .u8(0xff)
                                        .u8(0xff)
                                        .buf());
        int base = ws.m_dynamicsWorld.getNumCollisionObjects();
        ws.m_chunkMaps[0].setNeed(92, 92, 0);
        WorldNatives.stepSimulation(1.0f / 60.0f, 1, 1.0f / 60.0f);
        assertEquals(base + 9, ws.m_dynamicsWorld.getNumCollisionObjects());
        WorldNatives.stepSimulation(1.0f / 60.0f, 1, 1.0f / 60.0f);
        assertEquals(base, ws.m_dynamicsWorld.getNumCollisionObjects());
        assertEquals(9, ws.m_solidPool.size());
        // the removed solids went back to the pool and are reused
        ws.m_chunkMaps[0].setNeed(92, 92, 0);
        WorldNatives.stepSimulation(1.0f / 60.0f, 1, 1.0f / 60.0f);
        assertEquals(base + 9, ws.m_dynamicsWorld.getNumCollisionObjects());
        assertEquals(0, ws.m_solidPool.size());
        assertTrue(impulses.isEmpty());
    }

    @Test
    void constraintIdsStartAtOne() {
        WorldSimulation ws = clientWorld();
        assertEquals(0, ws.m_constraintCounter);
        assertEquals(1, WorldNatives.addPhysicsObject(1.0f, 1.0f));
        assertEquals(2, WorldNatives.addPhysicsObject(2.0f, 1.0f));
        ws.removePhysicsObject(1);
        float[] out = new float[8];
        assertEquals(1, WorldNatives.getObjectPhysics(out));
        assertEquals(2.0f, out[0]);
    }
}
