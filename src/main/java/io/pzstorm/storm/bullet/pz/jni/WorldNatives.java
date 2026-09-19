// Port of PZ Java_zombie_core_physics_Bullet_* world JNI entries (PZBullet.cpp): ToBullet
// @0014e010, getPZBulletVersion @0014e190, isWorldInit @0014e1b0, initWorld @0014e1c0,
// destroyWorld @0014e350, activateChunkMap @0014e380, deactivateChunkMap @0014e3e0,
// scrollChunkMap @0014e430, setChunkMinMaxLevel @0014e4d0, stepSimulation @0014f580,
// getObjectPhysics @001517b0, createServerCell @00151d30, removeServerCell @00151d80,
// addPhysicsObject @00151dd0, throwPhysicsObject @00151e20 (no Java declaration),
// clearPhysicsMeshes @001532f0, definePhysicsMesh @00153340, getCorrectedWorldSpace @00153ad0,
// initPZBullet @001562d0, Start @0014dff0 / Finish @0014e000 (empty).
package io.pzstorm.storm.bullet.pz.jni;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import io.pzstorm.storm.bullet.pz.Chunk;
import io.pzstorm.storm.bullet.pz.ChunkLevel;
import io.pzstorm.storm.bullet.pz.PZBullet;
import io.pzstorm.storm.bullet.pz.PZDebugLog;
import io.pzstorm.storm.bullet.pz.PZDebugType;
import io.pzstorm.storm.bullet.pz.PZRagdoll;
import io.pzstorm.storm.bullet.pz.RagdollBuilder;
import io.pzstorm.storm.bullet.pz.SkeletonBone;
import io.pzstorm.storm.bullet.pz.WorldSimulation;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Bodies of the world JNI natives, same names and Java signatures as zombie.core.physics.Bullet.
 *
 * <p>Where the C++ calls ThrowNew and then returns, Java throws the same class and message (the
 * native return value is never seen by the Java caller). Where the C++ dereferences a null {@code
 * WorldSimulation::instance} without a check (scrollChunkMap, getCorrectedWorldSpace), Java raises
 * a NullPointerException. The GetPrimitiveArrayCritical "out of memory" branches cannot occur.
 */
public final class WorldNatives {

    private WorldNatives() {}

    private static final String LOC = "/usr/src/pz/pzbullet/PZBullet.cpp:";
    private static final String NULL_WS = "WorldSimulation::instance is null";

    private static void log(int line, String msg) {
        PZDebugLog.instance().log("PZBullet", PZDebugType.Debug, LOC + line, msg);
    }

    public static void Start() {}

    public static void Finish() {}

    /**
     * Command stream at the buffer's base address (GetDirectBufferAddress: position is ignored),
     * little-endian, terminated by 0xff. Command 8 = {x, y, minLevel, maxLevel, level} shorts then
     * square entries {x, y, count, shape[count]} up to 0xff. The four shape locals are not
     * re-initialised per square: when count &lt; 4 the unused slots keep the previous square's
     * values (the first ones hold stack garbage in C++, zero here). ChunkLevel.setShapes only reads
     * slots below count, so the stale values are never observed.
     */
    public static void ToBullet(ByteBuffer buf) {
        int[] shapes = new int[4];
        int p = 0;
        int cmd = u8(buf, p);
        while (cmd != 0xff) {
            int next = p + 1;
            if (cmd == 8) {
                int x = s16(buf, p + 1);
                int y = s16(buf, p + 3);
                int minLevel = s16(buf, p + 5);
                int maxLevel = s16(buf, p + 7);
                int level = s16(buf, p + 9);
                Chunk chunk = WorldSimulation.instance.getChunkForAnyPlayer(x, y);
                if (chunk != null) {
                    chunk.setMinMaxLevel(minLevel, maxLevel);
                    ChunkLevel lvl = chunk.getLevelData(level);
                    if (lvl != null && lvl.bodiesAdded) {
                        lvl.bodiesAdded = false;
                        lvl.removePhysicsBodies();
                    }
                }
                int q = p + 0xb;
                int bx = u8(buf, q);
                while (bx != 0xff) {
                    int by = u8(buf, q + 1);
                    int n = u8(buf, q + 2);
                    if (n != 0) {
                        shapes[0] = u8(buf, q + 3);
                        if (n != 1) {
                            shapes[1] = u8(buf, q + 4);
                            if (n != 2) {
                                shapes[2] = u8(buf, q + 5);
                                if (n != 3) {
                                    shapes[3] = u8(buf, q + 6);
                                }
                            }
                        }
                    }
                    q = q + 3 + n;
                    if (chunk != null) {
                        chunk.setShapes(bx, by, level, n, shapes);
                    }
                    bx = u8(buf, q);
                }
                next = q + 1;
            }
            p = next;
            cmd = u8(buf, p);
        }
    }

    private static int u8(ByteBuffer b, int i) {
        return b.get(i) & 0xff;
    }

    private static int s16(ByteBuffer b, int i) {
        return (short) ((b.get(i) & 0xff) | (b.get(i + 1) << 8));
    }

    public static String getPZBulletVersion() {
        return "1.0.0.28";
    }

    public static void initPZBullet() {
        PZBullet.Instance().Init();
        PZDebugLog.instance().init();
        SkeletonBone.ValidateAgainstJava();
        log(140, "PZBullet::Init complete.");
    }

    public static boolean isWorldInit() {
        return WorldSimulation.instance != null;
    }

    /**
     * Also selects which library is being modelled ({@link WorldSimulation#glBuild}), exactly as
     * zombie.core.physics.Bullet.init picks it: NoOpenGL only on a Unix dedicated server.
     */
    public static void initWorld(
            int minCellX,
            int minCellY,
            int maxCellX,
            int maxCellY,
            int offsetX,
            int offsetY,
            boolean isServer) {
        log(145, "PZBullet::Java_zombie_core_physics_Bullet_initWorld");
        if (WorldSimulation.instance != null) {
            throw new RuntimeException("WorldSimulation::instance != null");
        }
        try {
            WorldSimulation.glBuild =
                    !(zombie.network.GameServer.server && zombie.GameWindow.OSValidator.isUnix());
        } catch (Throwable t) {
            // no game classes (standalone harness/tests): keep the current setting
        }
        WorldSimulation ws =
                new WorldSimulation(
                        minCellX, minCellY, maxCellX, maxCellY, offsetX, offsetY, isServer);
        WorldSimulation.instance = ws;
        RagdollBuilder.Instance().initialize();
        log(154, "[Success] PZBullet::Java_zombie_core_physics_Bullet_initWorld");
    }

    public static void destroyWorld() {
        if (WorldSimulation.instance != null) {
            WorldSimulation.instance.destroy();
        }
    }

    public static void activateChunkMap(int pi, int wx, int wy, int width) {
        if (WorldSimulation.instance == null) {
            throw new NullPointerException("WorldSimulation::instance == null");
        }
        WorldSimulation.instance.activateChunkMap(pi, wx, wy, width);
    }

    public static void deactivateChunkMap(int pi) {
        if (WorldSimulation.instance == null) {
            throw new NullPointerException("WorldSimulation::instance == null");
        }
        WorldSimulation.instance.deactivateChunkMap(pi);
    }

    /** No instance check in C++ (reads instance-&gt;chunkMaps[pi] directly). */
    public static void scrollChunkMap(int pi, int dir) {
        WorldSimulation ws = WorldSimulation.instance;
        if (Integer.compareUnsigned(pi, 3) > 0 || ws.m_chunkMaps[pi] == null) {
            throw new NullPointerException("chunkmap is null");
        }
        switch (dir) {
            case 0 -> ws.scrollGroundLeft(pi);
            case 1 -> ws.scrollGroundRight(pi);
            case 2 -> ws.scrollGroundUp(pi);
            case 3 -> ws.scrollGroundDown(pi);
            default -> {}
        }
    }

    public static void setChunkMinMaxLevel(int wx, int wy, int minLevel, int maxLevel) {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new NullPointerException(NULL_WS);
        }
        for (int pi = 0; pi != 4; pi++) {
            Chunk c = ws.getChunk(pi, wx, wy);
            if (c != null) {
                c.setMinMaxLevel(minLevel, maxLevel);
            }
        }
    }

    public static void stepSimulation(float timeStep, int maxSubSteps, float fixedTimeStep) {
        if (WorldSimulation.instance == null) {
            throw new RuntimeException(NULL_WS);
        }
        WorldSimulation.instance.stepSimulation(
                (double) timeStep, maxSubSteps, (double) fixedTimeStep);
    }

    /**
     * Writes {id, x, y, z} per physics object (map order), returns the map size. The C++ does not
     * check the array length (heap overflow); Java throws ArrayIndexOutOfBoundsException (gap).
     */
    public static int getObjectPhysics(float[] out) {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new NullPointerException(NULL_WS);
        }
        int i = 0;
        for (Map.Entry<Integer, btRigidBody> e : ws.m_physicsObjects.entrySet()) {
            btVector3 o = e.getValue().m_worldTransform.m_origin;
            out[i] = (float) e.getKey().intValue();
            out[i + 1] = (float) o.x;
            out[i + 2] = (float) o.y;
            out[i + 3] = (float) o.z;
            i += 4;
        }
        return ws.m_physicsObjects.size();
    }

    public static void createServerCell(int cx, int cy) {
        if (WorldSimulation.instance == null) {
            throw new RuntimeException(NULL_WS);
        }
        WorldSimulation.instance.createServerCell(cx, cy);
    }

    public static void removeServerCell(int cx, int cy) {
        if (WorldSimulation.instance == null) {
            throw new RuntimeException(NULL_WS);
        }
        WorldSimulation.instance.removeServerCell(cx, cy);
    }

    public static int addPhysicsObject(float x, float y) {
        if (WorldSimulation.instance == null) {
            throw new NullPointerException(NULL_WS);
        }
        return WorldSimulation.instance.addPhysicsObject(x, y);
    }

    /** Exported by the library but not declared in zombie.core.physics.Bullet. */
    public static int throwPhysicsObject(float x, float y, float z, float vx, float vy, float vz) {
        if (WorldSimulation.instance == null) {
            throw new NullPointerException(NULL_WS);
        }
        return WorldSimulation.instance.throwPhysicsObject(x, y, z, vx, vy, vz);
    }

    public static void clearPhysicsMeshes() {
        if (WorldSimulation.instance == null) {
            throw new NullPointerException(NULL_WS);
        }
        WorldSimulation.instance.clearPhysicsMeshes();
    }

    public static void definePhysicsMesh(int idx, boolean compound, float[] points) {
        if (WorldSimulation.instance == null) {
            throw new NullPointerException(NULL_WS);
        }
        int len = points.length;
        WorldSimulation.instance.definePhysicsMesh(idx, compound, len / 3, points);
    }

    /**
     * Ragdoll root position (Ragdoll +0x68) as floats. Only length &gt;= 1 is checked before three
     * floats are written (C++ overflows a 1- or 2-element array; Java throws
     * ArrayIndexOutOfBoundsException, gap). No instance check (null dereference). A missing id
     * writes nothing.
     */
    public static void getCorrectedWorldSpace(int id, float[] out) {
        if (id < 0) {
            throw new IllegalArgumentException("id is invalid");
        }
        if (out.length < 1) {
            throw new IllegalArgumentException("output array is too small");
        }
        PZRagdoll r = WorldSimulation.instance.m_ragdolls.m_map.get(id);
        if (r != null) {
            btVector3 o = r.m_ragdoll.m_position;
            out[0] = (float) o.x;
            out[1] = (float) o.y;
            out[2] = (float) o.z;
        }
    }
}
