package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btConvexCast;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.narrowphase.btSubsimplexConvexCast;
import io.pzstorm.storm.bullet.collision.narrowphase.btVoronoiSimplexSolver;
import io.pzstorm.storm.bullet.collision.shapes.btConcaveShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleCallback;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleShape;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btConvexConcaveCollisionAlgorithm.{h,cpp}.
 * The member btConvexTriangleCallback is constructed after the base (C++ member init order) and
 * destroyed before it.
 */
public class btConvexConcaveCollisionAlgorithm extends btActivatingCollisionAlgorithm {
    /** sizeof(btConvexConcaveCollisionAlgorithm) in the x86-64 binary. */
    public static final int SIZEOF = 0xa0;

    boolean m_isSwapped;

    final btConvexTriangleCallback m_btConvexTriangleCallback;

    public btConvexConcaveCollisionAlgorithm(
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            boolean isSwapped) {
        super(ci, body0Wrap, body1Wrap);
        m_isSwapped = isSwapped;
        m_btConvexTriangleCallback =
                new btConvexTriangleCallback(ci.m_dispatcher1, body0Wrap, body1Wrap, isSwapped);
    }

    @Override
    public void destroy() {
        m_btConvexTriangleCallback.destroy();
        super.destroy();
    }

    @Override
    public void getAllContactManifolds(btAlignedObjectArray<btPersistentManifold> manifoldArray) {
        if (m_btConvexTriangleCallback.m_manifoldPtr != null) {
            manifoldArray.push_back(m_btConvexTriangleCallback.m_manifoldPtr);
        }
    }

    public void clearCache() {
        m_btConvexTriangleCallback.clearCache();
    }

    @Override
    public void processCollision(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        btCollisionObjectWrapper convexBodyWrap = m_isSwapped ? body1Wrap : body0Wrap;
        btCollisionObjectWrapper triBodyWrap = m_isSwapped ? body0Wrap : body1Wrap;

        if (triBodyWrap.getCollisionShape().isConcave()) {
            btConcaveShape concaveShape = (btConcaveShape) triBodyWrap.getCollisionShape();

            if (convexBodyWrap.getCollisionShape().isConvex()) {
                double collisionMarginTriangle = concaveShape.getMargin();

                resultOut.setPersistentManifold(m_btConvexTriangleCallback.m_manifoldPtr);
                m_btConvexTriangleCallback.setTimeStepAndCounters(
                        collisionMarginTriangle,
                        dispatchInfo,
                        convexBodyWrap,
                        triBodyWrap,
                        resultOut);

                m_btConvexTriangleCallback.m_manifoldPtr.setBodies(
                        convexBodyWrap.getCollisionObject(), triBodyWrap.getCollisionObject());

                concaveShape.processAllTriangles(
                        m_btConvexTriangleCallback,
                        m_btConvexTriangleCallback.getAabbMin(),
                        m_btConvexTriangleCallback.getAabbMax());

                resultOut.refreshContactPoints();

                m_btConvexTriangleCallback.clearWrapperData();
            }
        }
    }

    /** Function-local struct in calculateTimeOfImpact. */
    static class LocalTriangleSphereCastCallback extends btTriangleCallback {
        final btTransform m_ccdSphereFromTrans;
        final btTransform m_ccdSphereToTrans;
        final btTransform m_meshTransform = new btTransform();

        double m_ccdSphereRadius;
        double m_hitFraction;

        LocalTriangleSphereCastCallback(
                btTransform from, btTransform to, double ccdSphereRadius, double hitFraction) {
            m_ccdSphereFromTrans = new btTransform(from);
            m_ccdSphereToTrans = new btTransform(to);
            m_ccdSphereRadius = ccdSphereRadius;
            m_hitFraction = hitFraction;
        }

        @Override
        public void processTriangle(btVector3[] triangle, int partId, int triangleIndex) {
            // do a swept sphere for now
            btTransform ident = new btTransform();
            ident.setIdentity();
            btConvexCast.CastResult castResult = new btConvexCast.CastResult();
            castResult.m_fraction = m_hitFraction;
            btSphereShape pointShape = new btSphereShape(m_ccdSphereRadius);
            btTriangleShape triShape = new btTriangleShape(triangle[0], triangle[1], triangle[2]);
            btVoronoiSimplexSolver simplexSolver = new btVoronoiSimplexSolver();
            btSubsimplexConvexCast convexCaster =
                    new btSubsimplexConvexCast(pointShape, triShape, simplexSolver);

            if (convexCaster.calcTimeOfImpact(
                    m_ccdSphereFromTrans, m_ccdSphereToTrans, ident, ident, castResult)) {
                if (m_hitFraction > castResult.m_fraction) m_hitFraction = castResult.m_fraction;
            }
        }
    }

    @Override
    public double calculateTimeOfImpact(
            btCollisionObject body0,
            btCollisionObject body1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        btCollisionObject convexbody = m_isSwapped ? body1 : body0;
        btCollisionObject triBody = m_isSwapped ? body0 : body1;

        // only perform CCD above a certain threshold
        double squareMot0 =
                (convexbody
                                .getInterpolationWorldTransform()
                                .getOrigin()
                                .sub(convexbody.getWorldTransform().getOrigin()))
                        .length2();
        if (squareMot0 < convexbody.getCcdSquareMotionThreshold()) {
            return 1.;
        }

        btTransform triInv = triBody.getWorldTransform().inverse();
        btTransform convexFromLocal = triInv.mul(convexbody.getWorldTransform());
        btTransform convexToLocal = triInv.mul(convexbody.getInterpolationWorldTransform());

        if (triBody.getCollisionShape().isConcave()) {
            btVector3 rayAabbMin = new btVector3(convexFromLocal.getOrigin());
            rayAabbMin.setMin(convexToLocal.getOrigin());
            btVector3 rayAabbMax = new btVector3(convexFromLocal.getOrigin());
            rayAabbMax.setMax(convexToLocal.getOrigin());
            double ccdRadius0 = convexbody.getCcdSweptSphereRadius();
            rayAabbMin.subLocal(new btVector3(ccdRadius0, ccdRadius0, ccdRadius0));
            rayAabbMax.addLocal(new btVector3(ccdRadius0, ccdRadius0, ccdRadius0));

            double curHitFraction = 1.; // is this available?
            LocalTriangleSphereCastCallback raycastCallback =
                    new LocalTriangleSphereCastCallback(
                            convexFromLocal,
                            convexToLocal,
                            convexbody.getCcdSweptSphereRadius(),
                            curHitFraction);

            raycastCallback.m_hitFraction = convexbody.getHitFraction();

            btCollisionObject concavebody = triBody;

            btConcaveShape triangleMesh = (btConcaveShape) concavebody.getCollisionShape();

            if (triangleMesh != null) {
                triangleMesh.processAllTriangles(raycastCallback, rayAabbMin, rayAabbMax);
            }

            if (raycastCallback.m_hitFraction < convexbody.getHitFraction()) {
                convexbody.setHitFraction(raycastCallback.m_hitFraction);
                return raycastCallback.m_hitFraction;
            }
        }

        return 1.;
    }

    public static class CreateFunc extends btCollisionAlgorithmCreateFunc {
        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btConvexConcaveCollisionAlgorithm a =
                    new btConvexConcaveCollisionAlgorithm(ci, body0Wrap, body1Wrap, false);
            a.m_allocAddress = mem;
            return a;
        }
    }

    public static class SwappedCreateFunc extends btCollisionAlgorithmCreateFunc {
        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btConvexConcaveCollisionAlgorithm a =
                    new btConvexConcaveCollisionAlgorithm(ci, body0Wrap, body1Wrap, true);
            a.m_allocAddress = mem;
            return a;
        }
    }
}
