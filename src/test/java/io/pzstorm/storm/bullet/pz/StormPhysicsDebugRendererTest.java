package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.StormPhysicsDebugRenderer;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The {@code Java_zombie_core_physics_PhysicsDebugRenderer_*} entry prologues (0x903c0..). */
class StormPhysicsDebugRendererTest implements UnitTest {

    private static final int HIDE = btCollisionObject.CF_DISABLE_VISUALIZE_OBJECT;

    private CaptureSink sink;

    @BeforeEach
    void setUp() {
        teardownWorld();
        PhysicsDebugRenderer.resetForTests();
        sink = new CaptureSink();
        PhysicsDebugRenderer.sink = sink;
    }

    @AfterEach
    void tearDown() {
        teardownWorld();
        PhysicsDebugRenderer.resetForTests();
    }

    private static void teardownWorld() {
        // not destroy(): it upcasts every object to btRigidBody, and these are plain objects
        WorldSimulation.instance = null;
        WorldSimulation.glBuild = false;
        btGlobals.gDynamicsWorld = null;
        // globals the WorldSimulation ctor sets
        btGlobals.gContactAddedCallback = null;
        btGlobals.gDeactivationTime = 2.0;
    }

    private static WorldSimulation newWorld() {
        WorldSimulation ws = new WorldSimulation(0, 0, 66, 63, 0, 0, false);
        WorldSimulation.instance = ws;
        return ws;
    }

    private static void assertNoWorld(Executable e) {
        RuntimeException ex = assertThrows(RuntimeException.class, e);
        assertEquals(RuntimeException.class, ex.getClass());
        assertEquals("gDynamicsWorld is null", ex.getMessage());
    }

    @Test
    void nullWorldThrowsBeforeThePrologue() {
        Object self = new Object();
        assertNoWorld(() -> StormPhysicsDebugRenderer.n_debugDrawWorld(self, 0, 0, 0, 7));
        assertNoWorld(() -> StormPhysicsDebugRenderer.renderRagdoll(self, 1, 0, 0));
        assertNoWorld(() -> StormPhysicsDebugRenderer.renderBallistics(self, 1, 0, 0));
        assertNoWorld(() -> StormPhysicsDebugRenderer.renderBallisticsTarget(self, 1, 0, 0));
        assertNoWorld(() -> StormPhysicsDebugRenderer.renderVehicle(self, 1, 0, 0));
        // the throw precedes PhysicsDebugRenderer::instance allocation and method lookup
        assertNull(PhysicsDebugRenderer.instance);
        assertTrue(sink.probes.isEmpty());
        assertTrue(sink.calls.isEmpty());
    }

    @Test
    void renderEntriesWriteTheOffsetThenLookUpTheId() {
        WorldSimulation ws = newWorld();
        btVector3 off = ws.m_debugDrawer.m_offset;
        Object self = new Object();

        StormPhysicsDebugRenderer.renderVehicle(self, 99, -3, 16777217);
        assertEquals(List.of(-3.0, 0.0, 16777217.0, 0.0), List.of(off.x, off.y, off.z, off.w));
        assertNotNull(PhysicsDebugRenderer.instance);
        assertEquals(5, sink.probes.size());

        StormPhysicsDebugRenderer.renderRagdoll(self, 99, 4, 5);
        assertEquals(List.of(4.0, 0.0, 5.0, 0.0), List.of(off.x, off.y, off.z, off.w));
        StormPhysicsDebugRenderer.renderBallistics(self, 99, 6, 7);
        assertEquals(List.of(6.0, 0.0, 7.0, 0.0), List.of(off.x, off.y, off.z, off.w));
        StormPhysicsDebugRenderer.renderBallisticsTarget(self, 99, -8, -9);
        assertEquals(List.of(-8.0, 0.0, -9.0, 0.0), List.of(off.x, off.y, off.z, off.w));
        StormPhysicsDebugRenderer.n_debugDrawWorld(self, 10, -11, 0, 7);
        assertEquals(List.of(10.0, 0.0, -11.0, 0.0), List.of(off.x, off.y, off.z, off.w));

        // missing ids draw nothing; lookup happens only once
        assertTrue(sink.calls.isEmpty());
        assertEquals(5, sink.probes.size());
    }

    @Test
    void renderRagdollByIdHasNoWorldCheckAndKeepsTheStaleOffset() {
        WorldSimulation ws = newWorld();
        Object self = new Object();
        StormPhysicsDebugRenderer.renderVehicle(self, 99, 12, 34);
        btGlobals.gDynamicsWorld = null;
        StormPhysicsDebugRenderer.renderRagdollByID(self, 99); // no throw
        btVector3 off = ws.m_debugDrawer.m_offset;
        assertEquals(List.of(12.0, 0.0, 34.0, 0.0), List.of(off.x, off.y, off.z, off.w));
        assertTrue(sink.calls.isEmpty());
    }

    @Test
    void failedMethodLookupReturnsSilentlyWithoutOffsetWrite() {
        WorldSimulation ws = newWorld();
        PhysicsDebugRenderer.sink = new CaptureSink("drawCapsule");
        Object self = new Object();
        assertThrows(
                NoSuchMethodError.class,
                () -> StormPhysicsDebugRenderer.renderVehicle(self, 1, 5, 6));
        StormPhysicsDebugRenderer.renderVehicle(self, 1, 5, 6);
        btVector3 off = ws.m_debugDrawer.m_offset;
        assertEquals(List.of(0.0, 0.0, 0.0, 0.0), List.of(off.x, off.y, off.z, off.w));
    }

    private static btCollisionObject addObject(WorldSimulation ws, double z, int initialFlags) {
        btCollisionObject o = new btCollisionObject();
        o.setCollisionShape(new btSphereShape(0.5));
        o.m_collisionFlags = initialFlags;
        o.setUserPointer(new BulletObject(o, 0, 0, z, 0));
        ws.m_dynamicsWorld.addCollisionObject(o);
        return o;
    }

    @Test
    void debugDrawWorldTogglesVisualizationByLevel() {
        WorldSimulation ws = newWorld();
        btCollisionObject below = addObject(ws, 0.999, 0);
        btCollisionObject low = addObject(ws, 1.0, HIDE);
        btCollisionObject high = addObject(ws, 2.0, HIDE | 1);
        btCollisionObject above = addObject(ws, 2.0001, 1);
        btCollisionObject nan = addObject(ws, Double.NaN, 0);
        btCollisionObject noUser = new btCollisionObject();
        noUser.setCollisionShape(new btSphereShape(0.5));
        noUser.m_collisionFlags = HIDE;
        ws.m_dynamicsWorld.addCollisionObject(noUser);

        StormPhysicsDebugRenderer.n_debugDrawWorld(new Object(), 0, 0, 1, 2);

        assertEquals(HIDE, below.m_collisionFlags);
        assertEquals(0, low.m_collisionFlags);
        assertEquals(1, high.m_collisionFlags);
        assertEquals(HIDE | 1, above.m_collisionFlags);
        assertEquals(HIDE, nan.m_collisionFlags);
        assertEquals(HIDE, noUser.m_collisionFlags); // null user pointer: skipped
    }

    @Test
    void glBuildWorldDrawsOnlyVisibleObjectsThroughTheSink() {
        WorldSimulation.glBuild = true;
        WorldSimulation ws = newWorld();
        addObject(ws, 0.0, 0);
        StormPhysicsDebugRenderer.n_debugDrawWorld(new Object(), 0, 0, 1, 2);
        assertTrue(sink.calls.isEmpty(), () -> String.join("\n", sink.calls));
        StormPhysicsDebugRenderer.n_debugDrawWorld(new Object(), 0, 0, 0, 0);
        assertTrue(!sink.calls.isEmpty());
    }
}
