package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btDiscreteCollisionDetectorInterface;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btScalar;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btBoxBoxCollisionAlgorithm.{h,cpp}
 * (USE_PERSISTENT_CONTACTS defined).
 */
public class btBoxBoxCollisionAlgorithm extends btActivatingCollisionAlgorithm {
    /** sizeof(btBoxBoxCollisionAlgorithm) in the x86-64 binary. */
    public static final int SIZEOF = 0x20;

    boolean m_ownManifold;
    btPersistentManifold m_manifoldPtr;

    public btBoxBoxCollisionAlgorithm(
            btPersistentManifold mf,
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap) {
        super(ci, body0Wrap, body1Wrap);
        m_ownManifold = false;
        m_manifoldPtr = mf;
        if (m_manifoldPtr == null
                && m_dispatcher.needsCollision(
                        body0Wrap.getCollisionObject(), body1Wrap.getCollisionObject())) {
            m_manifoldPtr =
                    m_dispatcher.getNewManifold(
                            body0Wrap.getCollisionObject(), body1Wrap.getCollisionObject());
            m_ownManifold = true;
        }
    }

    @Override
    public void destroy() {
        if (m_ownManifold) {
            if (m_manifoldPtr != null) m_dispatcher.releaseManifold(m_manifoldPtr);
        }
        super.destroy();
    }

    @Override
    public void processCollision(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        if (m_manifoldPtr == null) return;

        btBoxShape box0 = (btBoxShape) body0Wrap.getCollisionShape();
        btBoxShape box1 = (btBoxShape) body1Wrap.getCollisionShape();

        resultOut.setPersistentManifold(m_manifoldPtr);

        btDiscreteCollisionDetectorInterface.ClosestPointInput input =
                new btDiscreteCollisionDetectorInterface.ClosestPointInput();
        input.m_maximumDistanceSquared = btScalar.BT_LARGE_FLOAT;
        input.m_transformA.set(body0Wrap.getWorldTransform());
        input.m_transformB.set(body1Wrap.getWorldTransform());

        btBoxBoxDetector detector = new btBoxBoxDetector(box0, box1);
        detector.getClosestPoints(input, resultOut, dispatchInfo.m_debugDraw);

        if (m_ownManifold) {
            resultOut.refreshContactPoints();
        }
    }

    @Override
    public double calculateTimeOfImpact(
            btCollisionObject body0,
            btCollisionObject body1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        return (double) 1.f;
    }

    @Override
    public void getAllContactManifolds(btAlignedObjectArray<btPersistentManifold> manifoldArray) {
        if (m_manifoldPtr != null && m_ownManifold) {
            manifoldArray.push_back(m_manifoldPtr);
        }
    }

    public static class CreateFunc extends btCollisionAlgorithmCreateFunc {
        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            int bbsize = SIZEOF;
            long ptr = ci.m_dispatcher1.allocateCollisionAlgorithm(bbsize);
            btBoxBoxCollisionAlgorithm a =
                    new btBoxBoxCollisionAlgorithm(null, ci, body0Wrap, body1Wrap);
            a.m_allocAddress = ptr;
            return a;
        }
    }
}
