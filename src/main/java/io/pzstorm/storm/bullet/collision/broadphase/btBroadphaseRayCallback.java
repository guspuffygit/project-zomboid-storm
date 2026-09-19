// Port of BulletCollision/BroadphaseCollision/btBroadphaseInterface.h (Bullet 2.82), struct
// btBroadphaseRayCallback (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btBroadphaseRayCallback extends btBroadphaseAabbCallback {
    /** Added some cached data to accelerate ray-AABB tests. */
    public final btVector3 m_rayDirectionInverse = new btVector3();

    /** {@code unsigned int m_signs[3]} (values 0/1). */
    public final int[] m_signs = new int[3];

    public double m_lambda_max;
}
