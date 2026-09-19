package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btActivatingCollisionAlgorithm.{h,cpp}. All
 * activation code is commented out upstream; this is a pass-through base class.
 */
public abstract class btActivatingCollisionAlgorithm extends btCollisionAlgorithm {

    public btActivatingCollisionAlgorithm(btCollisionAlgorithmConstructionInfo ci) {
        super(ci);
    }

    public btActivatingCollisionAlgorithm(
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap) {
        super(ci);
    }

    /** {@code virtual ~btActivatingCollisionAlgorithm()} (empty). */
    @Override
    public void destroy() {
        super.destroy();
    }
}
