// Port of BulletCollision/BroadphaseCollision/btCollisionAlgorithm.h (Bullet 2.82), struct
// btCollisionAlgorithmConstructionInfo (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;

public class btCollisionAlgorithmConstructionInfo {
    public btDispatcher m_dispatcher1;
    public btPersistentManifold m_manifold;

    public btCollisionAlgorithmConstructionInfo() {
        m_dispatcher1 = null;
        m_manifold = null;
    }

    /** m_manifold is left uninitialized in C++; null here. */
    public btCollisionAlgorithmConstructionInfo(btDispatcher dispatcher, int temp) {
        m_dispatcher1 = dispatcher;
    }
}
