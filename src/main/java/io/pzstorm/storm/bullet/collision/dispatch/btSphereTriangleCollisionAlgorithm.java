package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btDiscreteCollisionDetectorInterface;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleShape;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btScalar;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btSphereTriangleCollisionAlgorithm.{h,cpp}.
 */
public class btSphereTriangleCollisionAlgorithm extends btActivatingCollisionAlgorithm {
    /** sizeof(btSphereTriangleCollisionAlgorithm) in the x86-64 binary. */
    public static final int SIZEOF = 0x28;

    boolean m_ownManifold;
    btPersistentManifold m_manifoldPtr;
    boolean m_swapped;

    public btSphereTriangleCollisionAlgorithm(
            btPersistentManifold mf,
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            boolean swapped) {
        super(ci, body0Wrap, body1Wrap);
        m_ownManifold = false;
        m_manifoldPtr = mf;
        m_swapped = swapped;
        if (m_manifoldPtr == null) {
            m_manifoldPtr =
                    m_dispatcher.getNewManifold(
                            body0Wrap.getCollisionObject(), body1Wrap.getCollisionObject());
            m_ownManifold = true;
        }
    }

    public btSphereTriangleCollisionAlgorithm(btCollisionAlgorithmConstructionInfo ci) {
        super(ci);
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
            btCollisionObjectWrapper col0Wrap,
            btCollisionObjectWrapper col1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        if (m_manifoldPtr == null) return;

        btCollisionObjectWrapper sphereObjWrap = m_swapped ? col1Wrap : col0Wrap;
        btCollisionObjectWrapper triObjWrap = m_swapped ? col0Wrap : col1Wrap;

        btSphereShape sphere = (btSphereShape) sphereObjWrap.getCollisionShape();
        btTriangleShape triangle = (btTriangleShape) triObjWrap.getCollisionShape();

        /// report a contact. internally this will be kept persistent, and contact reduction is done
        resultOut.setPersistentManifold(m_manifoldPtr);
        SphereTriangleDetector detector =
                new SphereTriangleDetector(
                        sphere, triangle, m_manifoldPtr.getContactBreakingThreshold());

        btDiscreteCollisionDetectorInterface.ClosestPointInput input =
                new btDiscreteCollisionDetectorInterface.ClosestPointInput();
        input.m_maximumDistanceSquared = btScalar.BT_LARGE_FLOAT; // / @todo: tighter bounds
        input.m_transformA.set(sphereObjWrap.getWorldTransform());
        input.m_transformB.set(triObjWrap.getWorldTransform());

        boolean swapResults = m_swapped;

        detector.getClosestPoints(input, resultOut, dispatchInfo.m_debugDraw, swapResults);

        if (m_ownManifold) resultOut.refreshContactPoints();
    }

    @Override
    public double calculateTimeOfImpact(
            btCollisionObject col0,
            btCollisionObject col1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        // not yet
        return 1.;
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
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btSphereTriangleCollisionAlgorithm a =
                    new btSphereTriangleCollisionAlgorithm(
                            ci.m_manifold, ci, body0Wrap, body1Wrap, m_swapped);
            a.m_allocAddress = mem;
            return a;
        }
    }
}
