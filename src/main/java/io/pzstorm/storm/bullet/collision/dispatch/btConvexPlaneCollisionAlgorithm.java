package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btStaticPlaneShape;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btConvexPlaneCollisionAlgorithm.{h,cpp}.
 */
public class btConvexPlaneCollisionAlgorithm extends btCollisionAlgorithm {
    /** sizeof(btConvexPlaneCollisionAlgorithm) in the x86-64 binary. */
    public static final int SIZEOF = 0x30;

    boolean m_ownManifold;
    btPersistentManifold m_manifoldPtr;
    boolean m_isSwapped;
    int m_numPerturbationIterations;
    int m_minimumPointsPerturbationThreshold;

    public btConvexPlaneCollisionAlgorithm(
            btPersistentManifold mf,
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper col0Wrap,
            btCollisionObjectWrapper col1Wrap,
            boolean isSwapped,
            int numPerturbationIterations,
            int minimumPointsPerturbationThreshold) {
        super(ci);
        m_ownManifold = false;
        m_manifoldPtr = mf;
        m_isSwapped = isSwapped;
        m_numPerturbationIterations = numPerturbationIterations;
        m_minimumPointsPerturbationThreshold = minimumPointsPerturbationThreshold;
        btCollisionObjectWrapper convexObjWrap = m_isSwapped ? col1Wrap : col0Wrap;
        btCollisionObjectWrapper planeObjWrap = m_isSwapped ? col0Wrap : col1Wrap;

        if (m_manifoldPtr == null
                && m_dispatcher.needsCollision(
                        convexObjWrap.getCollisionObject(), planeObjWrap.getCollisionObject())) {
            m_manifoldPtr =
                    m_dispatcher.getNewManifold(
                            convexObjWrap.getCollisionObject(), planeObjWrap.getCollisionObject());
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

    public void collideSingleContact(
            btQuaternion perturbeRot,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        btCollisionObjectWrapper convexObjWrap = m_isSwapped ? body1Wrap : body0Wrap;
        btCollisionObjectWrapper planeObjWrap = m_isSwapped ? body0Wrap : body1Wrap;

        btConvexShape convexShape = (btConvexShape) convexObjWrap.getCollisionShape();
        btStaticPlaneShape planeShape = (btStaticPlaneShape) planeObjWrap.getCollisionShape();

        boolean hasCollision = false;
        btVector3 planeNormal = planeShape.getPlaneNormal();
        double planeConstant = planeShape.getPlaneConstant();

        btTransform convexWorldTransform = new btTransform(convexObjWrap.getWorldTransform());
        btTransform convexInPlaneTrans = new btTransform();
        convexInPlaneTrans.set(
                planeObjWrap.getWorldTransform().inverse().mul(convexWorldTransform));
        // now perturbe the convex-world transform
        convexWorldTransform.getBasis().mulLocal(new btMatrix3x3(perturbeRot));
        btTransform planeInConvex = new btTransform();
        planeInConvex.set(convexWorldTransform.inverse().mul(planeObjWrap.getWorldTransform()));

        btVector3 vtx =
                convexShape.localGetSupportingVertex(
                        planeInConvex.getBasis().mul(planeNormal.negate()));

        btVector3 vtxInPlane = convexInPlaneTrans.transform(vtx);
        double distance = (planeNormal.dot(vtxInPlane) - planeConstant);

        btVector3 vtxInPlaneProjected = vtxInPlane.sub(planeNormal.mul(distance));
        btVector3 vtxInPlaneWorld = planeObjWrap.getWorldTransform().mul(vtxInPlaneProjected);

        hasCollision = distance < m_manifoldPtr.getContactBreakingThreshold();
        resultOut.setPersistentManifold(m_manifoldPtr);
        if (hasCollision) {
            btVector3 normalOnSurfaceB =
                    planeObjWrap.getWorldTransform().getBasis().mul(planeNormal);
            btVector3 pOnB = new btVector3(vtxInPlaneWorld);
            resultOut.addContactPoint(normalOnSurfaceB, pOnB, distance);
        }
    }

    @Override
    public void processCollision(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        if (m_manifoldPtr == null) return;

        btCollisionObjectWrapper convexObjWrap = m_isSwapped ? body1Wrap : body0Wrap;
        btCollisionObjectWrapper planeObjWrap = m_isSwapped ? body0Wrap : body1Wrap;

        btConvexShape convexShape = (btConvexShape) convexObjWrap.getCollisionShape();
        btStaticPlaneShape planeShape = (btStaticPlaneShape) planeObjWrap.getCollisionShape();

        boolean hasCollision = false;
        btVector3 planeNormal = planeShape.getPlaneNormal();
        double planeConstant = planeShape.getPlaneConstant();
        btTransform planeInConvex = new btTransform();
        planeInConvex.set(
                convexObjWrap.getWorldTransform().inverse().mul(planeObjWrap.getWorldTransform()));
        btTransform convexInPlaneTrans = new btTransform();
        convexInPlaneTrans.set(
                planeObjWrap.getWorldTransform().inverse().mul(convexObjWrap.getWorldTransform()));

        btVector3 vtx =
                convexShape.localGetSupportingVertex(
                        planeInConvex.getBasis().mul(planeNormal.negate()));
        btVector3 vtxInPlane = convexInPlaneTrans.transform(vtx);
        double distance = (planeNormal.dot(vtxInPlane) - planeConstant);

        btVector3 vtxInPlaneProjected = vtxInPlane.sub(planeNormal.mul(distance));
        btVector3 vtxInPlaneWorld = planeObjWrap.getWorldTransform().mul(vtxInPlaneProjected);

        hasCollision = distance < m_manifoldPtr.getContactBreakingThreshold();
        resultOut.setPersistentManifold(m_manifoldPtr);
        if (hasCollision) {
            btVector3 normalOnSurfaceB =
                    planeObjWrap.getWorldTransform().getBasis().mul(planeNormal);
            btVector3 pOnB = new btVector3(vtxInPlaneWorld);
            resultOut.addContactPoint(normalOnSurfaceB, pOnB, distance);
        }

        // the perturbation algorithm doesn't work well with implicit surfaces such as spheres,
        // cylinder and cones; only enable it for polyhedral shapes
        if (convexShape.isPolyhedral()
                && resultOut.getPersistentManifold().getNumContacts()
                        < m_minimumPointsPerturbationThreshold) {
            btVector3 v0 = new btVector3(), v1 = new btVector3();
            btVector3.btPlaneSpace1(planeNormal, v0, v1);
            final double angleLimit = (double) 0.125f * btScalar.SIMD_PI;
            double perturbeAngle;
            double radius = convexShape.getAngularMotionDisc();
            perturbeAngle = btGlobals.gContactBreakingThreshold / radius;
            if (perturbeAngle > angleLimit) perturbeAngle = angleLimit;

            btQuaternion perturbeRot = new btQuaternion(v0, perturbeAngle);
            for (int i = 0; i < m_numPerturbationIterations; i++) {
                double iterationAngle =
                        i * (btScalar.SIMD_2_PI / (double) m_numPerturbationIterations);
                btQuaternion rotq = new btQuaternion(planeNormal, iterationAngle);
                collideSingleContact(
                        rotq.inverse().mul(perturbeRot).mul(rotq),
                        body0Wrap,
                        body1Wrap,
                        dispatchInfo,
                        resultOut);
            }
        }

        if (m_ownManifold) {
            if (m_manifoldPtr.getNumContacts() != 0) {
                resultOut.refreshContactPoints();
            }
        }
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
        public int m_numPerturbationIterations;
        public int m_minimumPointsPerturbationThreshold;

        public CreateFunc() {
            m_numPerturbationIterations = 1;
            m_minimumPointsPerturbationThreshold = 0;
        }

        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btConvexPlaneCollisionAlgorithm a;
            if (!m_swapped) {
                a =
                        new btConvexPlaneCollisionAlgorithm(
                                null,
                                ci,
                                body0Wrap,
                                body1Wrap,
                                false,
                                m_numPerturbationIterations,
                                m_minimumPointsPerturbationThreshold);
            } else {
                a =
                        new btConvexPlaneCollisionAlgorithm(
                                null,
                                ci,
                                body0Wrap,
                                body1Wrap,
                                true,
                                m_numPerturbationIterations,
                                m_minimumPointsPerturbationThreshold);
            }
            a.m_allocAddress = mem;
            return a;
        }
    }
}
