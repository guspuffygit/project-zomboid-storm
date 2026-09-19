// Port of BulletCollision/CollisionDispatch/btCollisionConfiguration.h (Bullet 2.82).
package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.linearmath.btPoolAllocator;

/**
 * btCollisionConfiguration allows to configure Bullet collision detection stack allocator size,
 * default collision algorithms and persistent manifold pool size
 */
public abstract class btCollisionConfiguration {
    /** memory pools */
    public abstract btPoolAllocator getPersistentManifoldPool();

    public abstract btPoolAllocator getCollisionAlgorithmPool();

    public abstract btCollisionAlgorithmCreateFunc getCollisionAlgorithmCreateFunc(
            int proxyType0, int proxyType1);
}
