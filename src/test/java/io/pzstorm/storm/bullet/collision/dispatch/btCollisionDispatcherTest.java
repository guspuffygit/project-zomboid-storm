package io.pzstorm.storm.bullet.collision.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import org.junit.jupiter.api.Test;

/**
 * Manifold pool bookkeeping of btCollisionDispatcher (btCollisionDispatcher.cpp getNewManifold /
 * releaseManifold): pool first, btAlignedAlloc fallback on overflow, null when
 * CD_DISABLE_CONTACTPOOL_DYNAMIC_ALLOCATION is set, swap-removal with m_index1a, LIFO pool reuse.
 */
class btCollisionDispatcherTest implements UnitTest {

    private static btCollisionObject obj() {
        btCollisionObject o = new btCollisionObject();
        o.setCollisionShape(new btSphereShape(0.5));
        return o;
    }

    private static btCollisionDispatcher dispatcher(int poolSize) {
        btDefaultCollisionConfiguration.btDefaultCollisionConstructionInfo ci =
                new btDefaultCollisionConfiguration.btDefaultCollisionConstructionInfo();
        ci.m_defaultMaxPersistentManifoldPoolSize = poolSize;
        return new btCollisionDispatcher(new btDefaultCollisionConfiguration(ci));
    }

    @Test
    void overflowFallsBackToHeapAndSwapRemoves() {
        btCollisionDispatcher d = dispatcher(2);
        btCollisionObject a = obj(), b = obj();
        btPersistentManifold m0 = d.getNewManifold(a, b);
        btPersistentManifold m1 = d.getNewManifold(a, b);
        btPersistentManifold m2 = d.getNewManifold(a, b);
        assertNotNull(m2);
        long a0 = d.m_manifoldAddr.get(m0), a1 = d.m_manifoldAddr.get(m1);
        assertTrue(d.getInternalManifoldPool().validPtr(a0));
        assertTrue(d.getInternalManifoldPool().validPtr(a1));
        assertFalse(d.getInternalManifoldPool().validPtr(d.m_manifoldAddr.get(m2)));
        assertEquals(0, d.getInternalManifoldPool().getFreeCount());

        d.releaseManifold(m0);
        assertEquals(2, d.getNumManifolds());
        assertSame(m2, d.getManifoldByIndexInternal(0));
        assertEquals(0, m2.m_index1a);
        assertSame(m1, d.getManifoldByIndexInternal(1));
        assertEquals(1, m1.m_index1a);

        btPersistentManifold m3 = d.getNewManifold(a, b);
        assertEquals(a0, (long) d.m_manifoldAddr.get(m3)); // LIFO pool reuse
        assertEquals(2, m3.m_index1a);
    }

    @Test
    void disabledDynamicAllocationReturnsNull() {
        btCollisionDispatcher d = dispatcher(1);
        d.setDispatcherFlags(
                d.getDispatcherFlags()
                        | btCollisionDispatcher.CD_DISABLE_CONTACTPOOL_DYNAMIC_ALLOCATION);
        btCollisionObject a = obj(), b = obj();
        assertNotNull(d.getNewManifold(a, b));
        assertNull(d.getNewManifold(a, b));
        assertEquals(1, d.getNumManifolds());
    }
}
