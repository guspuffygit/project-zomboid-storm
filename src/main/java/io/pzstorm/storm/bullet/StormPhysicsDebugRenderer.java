// Port of the Java_zombie_core_physics_PhysicsDebugRenderer_* JNI entries of libPZBullet64.so (GL
// build).
package io.pzstorm.storm.bullet;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.dynamics.btDynamicsWorld;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import io.pzstorm.storm.bullet.pz.BulletObject;
import io.pzstorm.storm.bullet.pz.PZBallistics;
import io.pzstorm.storm.bullet.pz.PZBallisticsTarget;
import io.pzstorm.storm.bullet.pz.PZRagdoll;
import io.pzstorm.storm.bullet.pz.PZVehicle;
import io.pzstorm.storm.bullet.pz.PhysicsDebugRenderer;
import io.pzstorm.storm.bullet.pz.WorldSimulation;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.This;

/**
 * Java replacement for the instance natives of {@code zombie.core.physics.PhysicsDebugRenderer}
 * (the client physics debug overlay). One {@code public static} method per JNI export, bound by
 * ByteBuddy ({@code io.pzstorm.storm.patch.bullet}); {@code self} is typed {@code Object} so the
 * signature never names the target class (naming it causes a boot-time ClassCircularityError).
 *
 * <p>Every entry except {@link #renderRagdollByID} starts with {@code if (gDynamicsWorld == NULL)
 * ThrowNew(RuntimeException, "gDynamicsWorld is null")}, then the {@link
 * PhysicsDebugRenderer#initInstance} prologue, then writes the drawer offset {@code (x, 0, z, 0)}
 * into {@code WorldSimulation::instance->m_debugDrawer} (WS+0x78) and draws. Draw output reaches
 * Java only through {@link io.pzstorm.storm.bullet.pz.PZGLDebugDrawer} upcalls.
 */
public final class StormPhysicsDebugRenderer {

    /** Divisor of the vehicle level computation (0x131118): chassis origin.y / this = level. */
    static final double LEVEL_HEIGHT = 2.449490010738373;

    private StormPhysicsDebugRenderer() {}

    private static void throwIfNoWorld() {
        if (btGlobals.gDynamicsWorld == null) {
            // FindClass("java/lang/RuntimeException") + ThrowNew; the entry returns.
            throw new RuntimeException("gDynamicsWorld is null");
        }
    }

    /** Stores {@code ((double)x, 0, (double)z, 0)} at WS+0x78 (the embedded drawer's offset). */
    private static void setDrawOffset(int x, int z) {
        btVector3 off = WorldSimulation.instance.m_debugDrawer.m_offset;
        off.x = (double) x;
        off.y = 0.0;
        off.z = (double) z;
        off.w = 0.0;
    }

    /**
     * {@code (float)(chassis.origin.y / 2.449490010738373)} floored in float (the SSE {@code
     * floorf} idiom at 0x9065c: exact, sign-preserving, NaN/|x|>=2^23 unchanged), widened.
     */
    private static double vehicleLevel(BulletObject up) {
        double y = up.vehicle.m_vehicle.getChassisWorldTransform().getOrigin().y;
        float f = (float) (y / LEVEL_HEIGHT);
        return (double) (float) Math.floor(f);
    }

    /**
     * {@code Java_..._n_1debugDrawWorld(env, self, ddwX, ddwY, minLevel, maxLevel)} (0x903c0).
     * Hides ({@code CF_DISABLE_VISUALIZE_OBJECT}) every collision object whose level is outside
     * {@code [minLevel, maxLevel]} (NaN = outside), shows the rest, then {@code debugDrawWorld()}.
     */
    public static void n_debugDrawWorld(
            @This Object self,
            @Argument(0) int ddwX,
            @Argument(1) int ddwY,
            @Argument(2) int minLevel,
            @Argument(3) int maxLevel) {
        throwIfNoWorld();
        if (!PhysicsDebugRenderer.initInstance(self)) {
            return;
        }
        setDrawOffset(ddwX, ddwY);
        btDynamicsWorld world = (btDynamicsWorld) btGlobals.gDynamicsWorld;
        btAlignedObjectArray<btCollisionObject> objs = world.getCollisionObjectArray();
        for (int i = 0; i < objs.size(); i++) {
            btCollisionObject obj = objs.get(i);
            Object ptr = obj.m_userObjectPointer;
            if (ptr == null) {
                continue;
            }
            BulletObject up = (BulletObject) ptr;
            // Both bounds recompute the level (two getChassisWorldTransform calls), as the native.
            double level = (up.type == 1 && up.vehicle != null) ? vehicleLevel(up) : up.z;
            boolean visible = false;
            if (level >= (double) minLevel) {
                double level2 = (up.type == 1 && up.vehicle != null) ? vehicleLevel(up) : up.z;
                visible = (double) maxLevel >= level2;
            }
            if (visible) {
                obj.m_collisionFlags &= ~btCollisionObject.CF_DISABLE_VISUALIZE_OBJECT;
            } else {
                obj.m_collisionFlags |= btCollisionObject.CF_DISABLE_VISUALIZE_OBJECT;
            }
        }
        ((btDynamicsWorld) btGlobals.gDynamicsWorld).debugDrawWorld();
    }

    /** {@code Java_..._renderRagdoll(env, self, id, x, z)} (0x90860). */
    public static void renderRagdoll(
            @This Object self, @Argument(0) int id, @Argument(1) int x, @Argument(2) int z) {
        throwIfNoWorld();
        if (!PhysicsDebugRenderer.initInstance(self)) {
            return;
        }
        setDrawOffset(x, z);
        WorldSimulation ws = WorldSimulation.instance;
        if (ws.m_ragdolls.containsKey(id)) {
            PZRagdoll r = ws.m_ragdolls.get(id);
            r.render();
        }
    }

    /**
     * {@code Java_..._renderRagdollByID(env, self, id)} (0x90b20) — exported but not declared in
     * the Java class. No world null check and no offset write: draws with whatever offset the last
     * entry left.
     */
    public static void renderRagdollByID(@This Object self, @Argument(0) int id) {
        if (!PhysicsDebugRenderer.initInstance(self)) {
            return;
        }
        WorldSimulation ws = WorldSimulation.instance;
        if (ws.m_ragdolls.containsKey(id)) {
            PZRagdoll r = ws.m_ragdolls.get(id);
            r.render();
        }
    }

    /** {@code Java_..._renderBallistics(env, self, id, x, z)} (0x91030). */
    public static void renderBallistics(
            @This Object self, @Argument(0) int id, @Argument(1) int x, @Argument(2) int z) {
        throwIfNoWorld();
        if (!PhysicsDebugRenderer.initInstance(self)) {
            return;
        }
        setDrawOffset(x, z);
        WorldSimulation ws = WorldSimulation.instance;
        if (ws.m_ballistics.containsKey(id)) {
            PZBallistics b = ws.m_ballistics.get(id);
            b.render();
        }
    }

    /** {@code Java_..._renderBallisticsTarget(env, self, id, x, z)} (0x912f0). */
    public static void renderBallisticsTarget(
            @This Object self, @Argument(0) int id, @Argument(1) int x, @Argument(2) int z) {
        throwIfNoWorld();
        if (!PhysicsDebugRenderer.initInstance(self)) {
            return;
        }
        setDrawOffset(x, z);
        WorldSimulation ws = WorldSimulation.instance;
        if (ws.m_ballisticsTargets.containsKey(id)) {
            PZBallisticsTarget t = ws.m_ballisticsTargets.get(id);
            t.render();
        }
    }

    /** {@code Java_..._renderVehicle(env, self, id, x, z)} (0x90d50). */
    public static void renderVehicle(
            @This Object self, @Argument(0) int id, @Argument(1) int x, @Argument(2) int z) {
        throwIfNoWorld();
        if (!PhysicsDebugRenderer.initInstance(self)) {
            return;
        }
        setDrawOffset(x, z);
        WorldSimulation ws = WorldSimulation.instance;
        if (ws.m_vehicles.containsKey(id)) {
            PZVehicle v = ws.m_vehicles.get(id);
            v.render();
        }
    }
}
