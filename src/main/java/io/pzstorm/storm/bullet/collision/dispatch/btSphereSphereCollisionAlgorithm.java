package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btSphereSphereCollisionAlgorithm.{h,cpp}
 * (CLEAR_MANIFOLD not defined).
 */
public class btSphereSphereCollisionAlgorithm extends btActivatingCollisionAlgorithm {
    /** sizeof(btSphereSphereCollisionAlgorithm) in the x86-64 binary. */
    public static final int SIZEOF = 0x20;

    boolean m_ownManifold;
    btPersistentManifold m_manifoldPtr;

    public btSphereSphereCollisionAlgorithm(
            btPersistentManifold mf,
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper col0Wrap,
            btCollisionObjectWrapper col1Wrap) {
        super(ci, col0Wrap, col1Wrap);
        m_ownManifold = false;
        m_manifoldPtr = mf;
        if (m_manifoldPtr == null) {
            m_manifoldPtr =
                    m_dispatcher.getNewManifold(
                            col0Wrap.getCollisionObject(), col1Wrap.getCollisionObject());
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
            btCollisionObjectWrapper col0Wrap,
            btCollisionObjectWrapper col1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        if (m_manifoldPtr == null) return;

        resultOut.setPersistentManifold(m_manifoldPtr);

        btSphereShape sphere0 = (btSphereShape) col0Wrap.getCollisionShape();
        btSphereShape sphere1 = (btSphereShape) col1Wrap.getCollisionShape();

        btVector3 diff =
                col0Wrap.getWorldTransform()
                        .getOrigin()
                        .sub(col1Wrap.getWorldTransform().getOrigin());
        double len = diff.length();
        double radius0 = sphere0.getRadius();
        double radius1 = sphere1.getRadius();

        // iff distance positive, don't generate a new contact
        if (len > (radius0 + radius1)) {
            resultOut.refreshContactPoints();
            return;
        }
        // distance (negative means penetration)
        double dist = len - (radius0 + radius1);

        btVector3 normalOnSurfaceB = new btVector3(1, 0, 0);
        if (len > btScalar.SIMD_EPSILON) {
            normalOnSurfaceB.set(diff.div(len));
        }

        // point on B (worldspace)
        btVector3 pos1 =
                col1Wrap.getWorldTransform().getOrigin().add(normalOnSurfaceB.mul(radius1));

        resultOut.addContactPoint(normalOnSurfaceB, pos1, dist);

        resultOut.refreshContactPoints();
    }

    @Override
    public double calculateTimeOfImpact(
            btCollisionObject col0,
            btCollisionObject col1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
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
                btCollisionObjectWrapper col0Wrap,
                btCollisionObjectWrapper col1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btSphereSphereCollisionAlgorithm a =
                    new btSphereSphereCollisionAlgorithm(null, ci, col0Wrap, col1Wrap);
            a.m_allocAddress = mem;
            return a;
        }
    }
}
