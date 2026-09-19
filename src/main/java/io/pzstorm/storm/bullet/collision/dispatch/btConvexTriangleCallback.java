package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleCallback;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleShape;
import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82 btConvexTriangleCallback (btConvexConcaveCollisionAlgorithm.{h,cpp}). For
 * each triangle in the concave mesh that overlaps the convex AABB, processTriangle is called.
 */
public class btConvexTriangleCallback extends btTriangleCallback {
    btCollisionObjectWrapper m_convexBodyWrap;
    btCollisionObjectWrapper m_triBodyWrap;

    final btVector3 m_aabbMin = new btVector3();
    final btVector3 m_aabbMax = new btVector3();

    btManifoldResult m_resultOut;
    btDispatcher m_dispatcher;
    btDispatcherInfo m_dispatchInfoPtr;
    double m_collisionMarginTriangle;

    public int m_triangleCount;

    public btPersistentManifold m_manifoldPtr;

    public btConvexTriangleCallback(
            btDispatcher dispatcher,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            boolean isSwapped) {
        m_dispatcher = dispatcher;
        m_dispatchInfoPtr = null;
        m_convexBodyWrap = isSwapped ? body1Wrap : body0Wrap;
        m_triBodyWrap = isSwapped ? body0Wrap : body1Wrap;

        // create the manifold from the dispatcher 'manifold pool'
        m_manifoldPtr =
                m_dispatcher.getNewManifold(
                        m_convexBodyWrap.getCollisionObject(), m_triBodyWrap.getCollisionObject());

        clearCache();
    }

    public void setTimeStepAndCounters(
            double collisionMarginTriangle,
            btDispatcherInfo dispatchInfo,
            btCollisionObjectWrapper convexBodyWrap,
            btCollisionObjectWrapper triBodyWrap,
            btManifoldResult resultOut) {
        m_convexBodyWrap = convexBodyWrap;
        m_triBodyWrap = triBodyWrap;

        m_dispatchInfoPtr = dispatchInfo;
        m_collisionMarginTriangle = collisionMarginTriangle;
        m_resultOut = resultOut;

        // recalc aabbs
        btTransform convexInTriangleSpace = new btTransform();
        convexInTriangleSpace.set(
                m_triBodyWrap
                        .getWorldTransform()
                        .inverse()
                        .mul(m_convexBodyWrap.getWorldTransform()));
        btCollisionShape convexShape = m_convexBodyWrap.getCollisionShape();
        convexShape.getAabb(convexInTriangleSpace, m_aabbMin, m_aabbMax);
        double extraMargin = collisionMarginTriangle;
        btVector3 extra = new btVector3(extraMargin, extraMargin, extraMargin);

        m_aabbMax.addLocal(extra);
        m_aabbMin.subLocal(extra);
    }

    public void clearWrapperData() {
        m_convexBodyWrap = null;
        m_triBodyWrap = null;
    }

    /** virtual ~btConvexTriangleCallback() */
    public void destroy() {
        clearCache();
        m_dispatcher.releaseManifold(m_manifoldPtr);
    }

    @Override
    public void processTriangle(btVector3[] triangle, int partId, int triangleIndex) {
        if (!btAabbUtil2.TestTriangleAgainstAabb2(triangle, 0, m_aabbMin, m_aabbMax)) {
            return;
        }

        btCollisionAlgorithmConstructionInfo ci = new btCollisionAlgorithmConstructionInfo();
        ci.m_dispatcher1 = m_dispatcher;

        if (m_convexBodyWrap.getCollisionShape().isConvex()) {
            btTriangleShape tm = new btTriangleShape(triangle[0], triangle[1], triangle[2]);
            tm.setMargin(m_collisionMarginTriangle);

            btCollisionObjectWrapper triObWrap =
                    new btCollisionObjectWrapper(
                            m_triBodyWrap,
                            tm,
                            m_triBodyWrap.getCollisionObject(),
                            m_triBodyWrap.getWorldTransform(),
                            partId,
                            triangleIndex);
            btCollisionAlgorithm colAlgo =
                    ci.m_dispatcher1.findAlgorithm(m_convexBodyWrap, triObWrap, m_manifoldPtr);

            btCollisionObjectWrapper tmpWrap = null;

            if (m_resultOut.getBody0Internal() == m_triBodyWrap.getCollisionObject()) {
                tmpWrap = m_resultOut.getBody0Wrap();
                m_resultOut.setBody0Wrap(triObWrap);
                m_resultOut.setShapeIdentifiersA(partId, triangleIndex);
            } else {
                tmpWrap = m_resultOut.getBody1Wrap();
                m_resultOut.setBody1Wrap(triObWrap);
                m_resultOut.setShapeIdentifiersB(partId, triangleIndex);
            }

            colAlgo.processCollision(m_convexBodyWrap, triObWrap, m_dispatchInfoPtr, m_resultOut);

            if (m_resultOut.getBody0Internal() == m_triBodyWrap.getCollisionObject()) {
                m_resultOut.setBody0Wrap(tmpWrap);
            } else {
                m_resultOut.setBody1Wrap(tmpWrap);
            }

            colAlgo.destroy();
            ci.m_dispatcher1.freeCollisionAlgorithm(colAlgo.m_allocAddress);
        }
    }

    public void clearCache() {
        m_dispatcher.clearManifold(m_manifoldPtr);
    }

    public btVector3 getAabbMin() {
        return m_aabbMin;
    }

    public btVector3 getAabbMax() {
        return m_aabbMax;
    }
}
