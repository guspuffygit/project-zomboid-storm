// Port of BulletCollision/CollisionDispatch/btCollisionCreateFunc.h (Bullet 2.82).
package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;

/** Used by the btCollisionDispatcher to register and create instances for btCollisionAlgorithm */
public class btCollisionAlgorithmCreateFunc {
    public boolean m_swapped;

    public btCollisionAlgorithmCreateFunc() {
        m_swapped = false;
    }

    public btCollisionAlgorithm CreateCollisionAlgorithm(
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap) {
        return null;
    }
}
