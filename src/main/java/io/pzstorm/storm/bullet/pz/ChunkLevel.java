// Port of PZ glue ChunkLevel (0x1810 bytes, Chunk.cpp): init @0014a110, alloc @0014d220,
// release @0014d2e0, clearShapes @0014a660, setShapes @0014a680, getLatestPhysicsShapesFromJava
// @0014a720, load @0014d1b0, addPhysicsBodies @0014cd80, removePhysicsBodies(int,int) @0014a800,
// removePhysicsBodies() @0014aba0, removePhysicsBodiesAndClearShapes @0014abe0, updateFloors
// @0014c270, updateWallsN @0014ac30, updateWallsS @0014b1e0, updateWallsW @0014b790,
// updateWallsE @0014bd00, updateStairs @0014ca30.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.BulletUpcalls;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import java.util.ArrayDeque;

/**
 * One level (z) of an 8x8 chunk: the per-square PhysicsShapes received from Java and the bodies
 * built from them. Square arrays are indexed {@code x * 8 + y} like the C++ {@code [x][y]} arrays.
 */
public class ChunkLevel {

    /** Static {@code std::deque<ChunkLevel*>} free list (ChunkLevel::pool). */
    public static final ArrayDeque<ChunkLevel> pool = new ArrayDeque<>();

    /** Number of ChunkLevels ever operator-new'd by {@link #alloc()} (DAT_0024ebf0). */
    public static long s_created;

    /** +0 */
    public Chunk chunk;

    /** +8 */
    public int level;

    /** +0xc */
    public boolean shapesSet;

    /** +0xd */
    public boolean bodiesAdded;

    /** +0xe */
    public boolean need;

    /** +0x10 PhysicsShapes[8][8] (4 ints each): index (x * 8 + y) * 4 + slot. */
    public final int[] shapes = new int[8 * 8 * 4];

    /** +0x410 btRigidBody*[8][8][4]: index (x * 8 + y) * 4 + slot. */
    public final btRigidBody[] bodies = new btRigidBody[8 * 8 * 4];

    /** +0xc10 */
    public final btRigidBody[] floors = new btRigidBody[64];

    /** +0xe10 */
    public final btRigidBody[] wallN = new btRigidBody[64];

    /** +0x1010 */
    public final btRigidBody[] wallW = new btRigidBody[64];

    /** +0x1210 */
    public final btRigidBody[] wallS = new btRigidBody[64];

    /** +0x1410 */
    public final btRigidBody[] wallE = new btRigidBody[64];

    /** +0x1610 (btCollisionObject*: stairs are not rigid bodies) */
    public final btCollisionObject[] stairs = new btCollisionObject[64];

    /** operator new(0x1810) + zero fill. */
    ChunkLevel() {}

    public static ChunkLevel alloc() {
        if (pool.isEmpty()) {
            s_created++;
            return new ChunkLevel();
        }
        return pool.pollFirst();
    }

    public void release() {
        pool.addFirst(this);
    }

    public ChunkLevel init(Chunk chunk, int level) {
        this.chunk = chunk;
        this.level = level;
        this.need = false;
        this.shapesSet = false;
        this.bodiesAdded = false;
        java.util.Arrays.fill(shapes, 0);
        java.util.Arrays.fill(bodies, null);
        java.util.Arrays.fill(floors, null);
        java.util.Arrays.fill(wallN, null);
        java.util.Arrays.fill(wallW, null);
        java.util.Arrays.fill(wallS, null);
        java.util.Arrays.fill(wallE, null);
        java.util.Arrays.fill(stairs, null);
        return this;
    }

    public void clearShapes(int x, int y) {
        int i = (x * 8 + y) * 4;
        shapes[i] = 0;
        shapes[i + 1] = 0;
        shapes[i + 2] = 0;
        shapes[i + 3] = 0;
    }

    /** {@code s} is the PhysicsShapes int[4] the caller built; slots past {@code n} are zeroed. */
    public void setShapes(int x, int y, int n, int[] s) {
        int i = (x * 8 + y) * 4;
        shapes[i] = n < 1 ? 0 : s[0];
        shapes[i + 1] = n < 2 ? 0 : s[1];
        shapes[i + 2] = n < 3 ? 0 : s[2];
        shapes[i + 3] = n < 4 ? 0 : s[3];
        shapesSet = true;
    }

    /** Upcall Bullet.updatePhysicsForLevelIfNeeded(wx, wy, level); the result is ignored. */
    public void getLatestPhysicsShapesFromJava() {
        BulletUpcalls.callUpdatePhysicsForLevelIfNeeded(chunk.wx, chunk.wy, level);
    }

    public void load() {
        if (need) {
            getLatestPhysicsShapesFromJava();
            if (need) {
                if (bodiesAdded) {
                    need = false;
                    return;
                }
                bodiesAdded = true;
                addPhysicsBodies();
                if (need) {
                    need = false;
                    return;
                }
            }
        }
        if (bodiesAdded) {
            bodiesAdded = false;
            removePhysicsBodies();
            need = false;
            return;
        }
        need = false;
    }

    public void addPhysicsBodies() {
        int lvl = level;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int wx = x + chunk.wx * 8;
                int wy = y + chunk.wy * 8;
                int base = (x * 8 + y) * 4;
                for (int j = 0; j < 4; j++) {
                    if (bodies[base + j] != null) {
                        continue;
                    }
                    int t = shapes[base + j];
                    if (t == 6) {
                        bodies[base + j] = WorldSimulation.instance.addTree(wx, wy, lvl);
                    } else if (t == 10) {
                        bodies[base + j] = WorldSimulation.instance.addSolidStairs(wx, wy, lvl);
                    } else if (t == 1) {
                        bodies[base + j] = WorldSimulation.instance.addSolid(wx, wy, lvl);
                    } else if (t >= 11) {
                        bodies[base + j] = WorldSimulation.instance.addMesh(wx, wy, lvl, t - 11);
                    }
                }
            }
        }
        updateFloors();
        updateWallsN();
        updateWallsW();
        updateWallsS();
        updateWallsE();
        updateStairs();
    }

    public void removePhysicsBodies(int x, int y) {
        WorldSimulation ws = WorldSimulation.instance;
        int c = x * 8 + y;
        int base = c * 4;
        for (int j = 0; j < 4; j++) {
            btRigidBody b = bodies[base + j];
            if (b != null) {
                int t = shapes[base + j];
                if (t == 6) {
                    ws.removeTree(b);
                } else if (t == 10 || t == 1) {
                    ws.removeSolid(b);
                } else if (t > 10) {
                    ws.removeMesh(b, t - 11);
                }
                bodies[base + j] = null;
            }
        }
        if (floors[c] != null) {
            ws.removeFloor(floors[c]);
            floors[c] = null;
        }
        if (wallN[c] != null) {
            ws.removeWallN(wallN[c]);
            wallN[c] = null;
        }
        if (wallW[c] != null) {
            ws.removeWallW(wallW[c]);
            wallW[c] = null;
        }
        if (wallS[c] != null) {
            ws.removeWallS(wallS[c]);
            wallS[c] = null;
        }
        if (wallE[c] != null) {
            // PZBullet bug kept: E walls are removed through removeWallS.
            ws.removeWallS(wallE[c]);
            wallE[c] = null;
        }
        if (stairs[c] != null) {
            ws.removeStairs(stairs[c]);
            stairs[c] = null;
        }
    }

    public void removePhysicsBodies() {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                removePhysicsBodies(x, y);
            }
        }
    }

    public void removePhysicsBodiesAndClearShapes() {
        if (bodiesAdded) {
            bodiesAdded = false;
            removePhysicsBodies();
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                clearShapes(x, y);
            }
        }
        shapesSet = false;
    }

    private boolean anyShape(int x, int y, int type) {
        int i = (x * 8 + y) * 4;
        return shapes[i] == type
                || shapes[i + 1] == type
                || shapes[i + 2] == type
                || shapes[i + 3] == type;
    }

    public void updateFloors() {
        // cell[x*8+y] = {a (width), b (height)} bytes
        int[] a = new int[64];
        int[] b = new int[64];
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                if (anyShape(x, y, 7)) {
                    a[x * 8 + y] = 1;
                    b[x * 8 + y] = 1;
                }
            }
        }
        if (!WorldSimulation.instance.isValidChunk(chunk.wx, chunk.wy)) {
            for (int i = 0; i < 64; i++) {
                a[i] = 1;
                b[i] = 1;
            }
        }
        // merge runs along x
        for (int y = 0; y < 8; y++) {
            int x = 0;
            while (x < 8) {
                if (a[x * 8 + y] == 0) {
                    x++;
                    continue;
                }
                int len = 1;
                while (x + len < 8 && a[(x + len) * 8 + y] != 0) {
                    a[(x + len) * 8 + y] = 0;
                    b[(x + len) * 8 + y] = 0;
                    len++;
                }
                a[x * 8 + y] = len;
                x += len;
            }
        }
        // merge equal-width runs along y (upwards into y-1)
        for (int y = 7; y >= 1; y--) {
            for (int x = 0; x < 8; x++) {
                int c = x * 8 + y;
                if (a[c] != 0 && a[c] == a[c - 1]) {
                    b[c - 1] = (b[c - 1] + b[c]) & 0xff;
                    a[c] = 0;
                    b[c] = 0;
                }
            }
        }
        WorldSimulation ws = WorldSimulation.instance;
        int lvl = level;
        int wy0 = chunk.wy * 8;
        int wx0 = chunk.wx * 8;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int c = x * 8 + y;
                int w = a[c];
                int h = b[c];
                btRigidBody cur = floors[c];
                if (cur == null) {
                    btCollisionShape shape = ws.getFloorShape(w, h);
                    if (shape != null) {
                        floors[c] = ws.addFloor(wx0 + x, wy0 + y, lvl, w, h);
                    }
                } else {
                    btCollisionShape curShape = cur.getCollisionShape();
                    btCollisionShape shape = ws.getFloorShape(w, h);
                    if (curShape != shape) {
                        if (curShape != null) {
                            ws.removeFloor(cur);
                            floors[c] = null;
                        }
                        if (shape != null) {
                            floors[c] = ws.addFloor(wx0 + x, wy0 + y, lvl, w, h);
                        }
                    }
                }
            }
        }
    }

    /** Greedy run merge along x (grid[x*8+y]) for each y: N/S walls. */
    private static void mergeRunsAlongX(int[] g) {
        for (int y = 0; y < 8; y++) {
            int x = 0;
            while (x < 8) {
                if (g[x * 8 + y] == 0) {
                    x++;
                    continue;
                }
                int len = 1;
                while (x + len < 8 && g[(x + len) * 8 + y] != 0) {
                    g[(x + len) * 8 + y] = 0;
                    len++;
                }
                g[x * 8 + y] = len;
                x += len;
            }
        }
    }

    /** Greedy run merge along y (grid[x*8+y]) for each x: W/E walls. */
    private static void mergeRunsAlongY(int[] g) {
        for (int x = 0; x < 8; x++) {
            int y = 0;
            while (y < 8) {
                if (g[x * 8 + y] == 0) {
                    y++;
                    continue;
                }
                int len = 1;
                while (y + len < 8 && g[x * 8 + y + len] != 0) {
                    g[x * 8 + y + len] = 0;
                    len++;
                }
                g[x * 8 + y] = len;
                y += len;
            }
        }
    }

    private int[] wallGrid(int type) {
        int[] g = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (anyShape(x, y, type)) {
                    g[x * 8 + y] = 1;
                }
            }
        }
        return g;
    }

    public void updateWallsN() {
        int[] g = wallGrid(2);
        if (WorldSimulation.instance.isValidChunkX(chunk.wx) && chunk.isAdjacentToEdgeOfWorld_S()) {
            for (int x = 0; x < 8; x++) {
                g[x * 8] = 1;
            }
        }
        mergeRunsAlongX(g);
        placeWalls(g, wallN, 0);
    }

    public void updateWallsS() {
        int[] g = wallGrid(4);
        if (WorldSimulation.instance.isValidChunkX(chunk.wx) && chunk.isAdjacentToEdgeOfWorld_N()) {
            for (int x = 0; x < 8; x++) {
                g[x * 8 + 7] = 1;
            }
        }
        mergeRunsAlongX(g);
        placeWalls(g, wallS, 2);
    }

    public void updateWallsW() {
        int[] g = wallGrid(3);
        if (chunk.isAdjacentToEdgeOfWorld_E() && WorldSimulation.instance.isValidChunkY(chunk.wy)) {
            for (int y = 0; y < 8; y++) {
                g[y] = 1;
            }
        }
        mergeRunsAlongY(g);
        placeWalls(g, wallW, 1);
    }

    public void updateWallsE() {
        int[] g = wallGrid(5);
        if (chunk.isAdjacentToEdgeOfWorld_W() && WorldSimulation.instance.isValidChunkY(chunk.wy)) {
            for (int y = 0; y < 8; y++) {
                g[56 + y] = 1;
            }
        }
        mergeRunsAlongY(g);
        placeWalls(g, wallE, 3);
    }

    private static btCollisionShape wallShape(WorldSimulation ws, int side, int len) {
        switch (side) {
            case 0:
                return ws.getWallNShape(len);
            case 1:
                return ws.getWallWShape(len);
            case 2:
                return ws.getWallSShape(len);
            default:
                return ws.getWallEShape(len);
        }
    }

    private static btRigidBody addWall(WorldSimulation ws, int side, int x, int y, int l, int len) {
        switch (side) {
            case 0:
                return ws.addWallN(x, y, l, len);
            case 1:
                return ws.addWallW(x, y, l, len);
            case 2:
                return ws.addWallS(x, y, l, len);
            default:
                return ws.addWallE(x, y, l, len);
        }
    }

    private static void removeWall(WorldSimulation ws, int side, btRigidBody b) {
        switch (side) {
            case 0:
                ws.removeWallN(b);
                break;
            case 1:
                ws.removeWallW(b);
                break;
            case 2:
                ws.removeWallS(b);
                break;
            default:
                ws.removeWallE(b);
                break;
        }
    }

    /** Shared tail of updateWalls{N,W,S,E}: y outer, x inner. side 0=N 1=W 2=S 3=E. */
    private void placeWalls(int[] g, btRigidBody[] slot, int side) {
        WorldSimulation ws = WorldSimulation.instance;
        int lvl = level;
        int wy = chunk.wy;
        int wx0 = chunk.wx * 8;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int c = x * 8 + y;
                int len = g[c];
                btRigidBody cur = slot[c];
                if (cur == null) {
                    btCollisionShape shape = wallShape(ws, side, len);
                    if (shape != null) {
                        slot[c] = addWall(ws, side, wx0 + x, wy * 8 + y, lvl, len);
                    }
                } else {
                    btCollisionShape curShape = cur.getCollisionShape();
                    btCollisionShape shape = wallShape(ws, side, len);
                    if (curShape != shape) {
                        if (curShape != null) {
                            removeWall(ws, side, cur);
                            slot[c] = null;
                        }
                        if (shape != null) {
                            slot[c] = addWall(ws, side, wx0 + x, wy * 8 + y, lvl, len);
                        }
                    }
                }
            }
        }
    }

    /**
     * Stairs slot: with no body, every shape slot of type 8/9 adds one (each overwrites the last;
     * earlier ones stay in the world, leaked, as in C++). With a body and no 8/9 slot left, it is
     * removed. The body pointer is read once, before any add.
     */
    public void updateStairs() {
        WorldSimulation ws = WorldSimulation.instance;
        int lvl = level;
        int wx0 = chunk.wx * 8;
        int worldY = chunk.wy << 3;
        for (int y = 0; y < 8; y++, worldY++) {
            for (int x = 0; x < 8; x++) {
                int c = x * 8 + y;
                int base = c * 4;
                btCollisionObject p = stairs[c];
                if (p == null) {
                    for (int j = 0; j < 4; j++) {
                        int t = shapes[base + j];
                        if (t == 8 || t == 9) {
                            stairs[c] = ws.addStairs(wx0 + x, lvl, worldY, t);
                        }
                    }
                } else {
                    boolean any = false;
                    for (int j = 0; j < 4; j++) {
                        int t = shapes[base + j];
                        if (t == 8 || t == 9) {
                            any = true;
                        }
                    }
                    if (!any) {
                        ws.removeStairs(p);
                        stairs[c] = null;
                    }
                }
            }
        }
    }
}
