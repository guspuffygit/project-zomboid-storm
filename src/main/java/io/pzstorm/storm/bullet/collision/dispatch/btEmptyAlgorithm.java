package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btEmptyCollisionAlgorithm.{h,cpp} (class
 * btEmptyAlgorithm). Stub for unsupported collision pairs.
 */
public class btEmptyAlgorithm extends btCollisionAlgorithm {
    /** sizeof(btEmptyAlgorithm) in the x86-64 binary (vptr + m_dispatcher). */
    public static final int SIZEOF = 0x10;

    public btEmptyAlgorithm(btCollisionAlgorithmConstructionInfo ci) {
        super(ci);
    }

    @Override
    public void processCollision(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {}

    @Override
    public double calculateTimeOfImpact(
            btCollisionObject body0,
            btCollisionObject body1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        return 1.;
    }

    @Override
    public void getAllContactManifolds(btAlignedObjectArray<btPersistentManifold> manifoldArray) {}

    public static class CreateFunc extends btCollisionAlgorithmCreateFunc {
        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btEmptyAlgorithm a = new btEmptyAlgorithm(ci);
            a.m_allocAddress = mem;
            return a;
        }
    }
}
