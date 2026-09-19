// Port of btTriangleMeshShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btTriangleMeshShape extends btConcaveShape {
    public final btVector3 m_localAabbMin = new btVector3();
    public final btVector3 m_localAabbMax = new btVector3();
    public btStridingMeshInterface m_meshInterface;

    /** protected: btTriangleMeshShape(btStridingMeshInterface* meshInterface) */
    protected btTriangleMeshShape(btStridingMeshInterface meshInterface) {
        super();
        m_meshInterface = meshInterface;
        m_shapeType = BroadphaseNativeTypes.TRIANGLE_MESH_SHAPE_PROXYTYPE;
        if (meshInterface.hasPremadeAabb()) {
            meshInterface.getPremadeAabb(m_localAabbMin, m_localAabbMax);
        } else {
            // C++ runs this inside the base constructor, where the virtual calls
            // localGetSupportingVertex/processAllTriangles still bind to btTriangleMeshShape's own
            // versions (not btBvhTriangleMeshShape's, whose m_bvh does not exist yet).
            recalcLocalAabbImpl(true);
        }
    }

    @Override
    public void getAabb(btTransform trans, btVector3 aabbMin, btVector3 aabbMax) {
        btVector3 localHalfExtents = m_localAabbMax.sub(m_localAabbMin).mul(0.5);
        localHalfExtents.addLocal(new btVector3(getMargin(), getMargin(), getMargin()));
        btVector3 localCenter = m_localAabbMax.add(m_localAabbMin).mul(0.5);

        btMatrix3x3 abs_b = trans.getBasis().absolute();

        btVector3 center = trans.transform(localCenter);

        btVector3 extent = localHalfExtents.dot3(abs_b.get(0), abs_b.get(1), abs_b.get(2));
        aabbMin.set(center.sub(extent));
        aabbMax.set(center.add(extent));
    }

    public void recalcLocalAabb() {
        recalcLocalAabbImpl(false);
    }

    /** {@code inCtor}: bind the virtual calls statically, as during C++ base construction. */
    private void recalcLocalAabbImpl(boolean inCtor) {
        for (int i = 0; i < 3; i++) {
            btVector3 vec = new btVector3(0.0, 0.0, 0.0);
            vec.set(i, 1.0);
            btVector3 tmp =
                    new btVector3(
                            inCtor
                                    ? localGetSupportingVertexBase(vec)
                                    : localGetSupportingVertex(vec));
            m_localAabbMax.set(i, tmp.get(i) + m_collisionMargin);
            vec.set(i, -1.0);
            tmp.set(inCtor ? localGetSupportingVertexBase(vec) : localGetSupportingVertex(vec));
            m_localAabbMin.set(i, tmp.get(i) - m_collisionMargin);
        }
    }

    /** File-local class SupportVertexCallback. */
    static final class SupportVertexCallback extends btTriangleCallback {
        final btVector3 m_supportVertexLocal = new btVector3(0.0, 0.0, 0.0);

        final btTransform m_worldTrans;
        double m_maxDot;
        final btVector3 m_supportVecLocal = new btVector3();

        SupportVertexCallback(btVector3 supportVecWorld, btTransform trans) {
            m_worldTrans = new btTransform(trans);
            m_maxDot = -btScalar.BT_LARGE_FLOAT;
            m_supportVecLocal.set(btMatrix3x3.mul(supportVecWorld, m_worldTrans.getBasis()));
        }

        @Override
        public void processTriangle(btVector3[] triangle, int partId, int triangleIndex) {
            for (int i = 0; i < 3; i++) {
                double dot = m_supportVecLocal.dot(triangle[i]);
                if (dot > m_maxDot) {
                    m_maxDot = dot;
                    m_supportVertexLocal.set(triangle[i]);
                }
            }
        }

        btVector3 GetSupportVertexWorldSpace() {
            return m_worldTrans.transform(m_supportVertexLocal);
        }

        btVector3 GetSupportVertexLocal() {
            return m_supportVertexLocal;
        }
    }

    @Override
    public void setLocalScaling(btVector3 scaling) {
        m_meshInterface.setScaling(scaling);
        recalcLocalAabb();
    }

    @Override
    public btVector3 getLocalScaling() {
        return m_meshInterface.getScaling();
    }

    /** Local struct FilteredCallback of processAllTriangles. */
    static final class FilteredCallback extends btInternalTriangleIndexCallback {
        final btTriangleCallback m_callback;
        final btVector3 m_aabbMin;
        final btVector3 m_aabbMax;

        FilteredCallback(btTriangleCallback callback, btVector3 aabbMin, btVector3 aabbMax) {
            m_callback = callback;
            m_aabbMin = new btVector3(aabbMin);
            m_aabbMax = new btVector3(aabbMax);
        }

        @Override
        public void internalProcessTriangleIndex(
                btVector3[] triangle, int partId, int triangleIndex) {
            if (btAabbUtil2.TestTriangleAgainstAabb2(triangle, 0, m_aabbMin, m_aabbMax)) {
                // check aabb in triangle-space, before doing this
                m_callback.processTriangle(triangle, partId, triangleIndex);
            }
        }
    }

    @Override
    public void processAllTriangles(
            btTriangleCallback callback, btVector3 aabbMin, btVector3 aabbMax) {
        processAllTrianglesBase(callback, aabbMin, aabbMax);
    }

    /** {@code btTriangleMeshShape::processAllTriangles} (qualified, non-virtual). */
    protected final void processAllTrianglesBase(
            btTriangleCallback callback, btVector3 aabbMin, btVector3 aabbMax) {
        FilteredCallback filterCallback = new FilteredCallback(callback, aabbMin, aabbMax);

        m_meshInterface.InternalProcessAllTriangles(filterCallback, aabbMin, aabbMax);
    }

    @Override
    public void calculateLocalInertia(double mass, btVector3 inertia) {
        // moving concave objects not supported
        inertia.setValue(0.0, 0.0, 0.0);
    }

    public btVector3 localGetSupportingVertex(btVector3 vec) {
        return localGetSupportingVertexImpl(vec, false);
    }

    private btVector3 localGetSupportingVertexBase(btVector3 vec) {
        return localGetSupportingVertexImpl(vec, true);
    }

    private btVector3 localGetSupportingVertexImpl(btVector3 vec, boolean inCtor) {
        btVector3 supportVertex = new btVector3();

        btTransform ident = new btTransform();
        ident.setIdentity();

        SupportVertexCallback supportCallback = new SupportVertexCallback(vec, ident);

        btVector3 aabbMax =
                new btVector3(
                        btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);

        if (inCtor) {
            processAllTrianglesBase(supportCallback, aabbMax.negate(), aabbMax);
        } else {
            processAllTriangles(supportCallback, aabbMax.negate(), aabbMax);
        }

        supportVertex.set(supportCallback.GetSupportVertexLocal());

        return supportVertex;
    }

    public btVector3 localGetSupportingVertexWithoutMargin(btVector3 vec) {
        return localGetSupportingVertex(vec);
    }

    public btStridingMeshInterface getMeshInterface() {
        return m_meshInterface;
    }

    public btVector3 getLocalAabbMin() {
        return m_localAabbMin;
    }

    public btVector3 getLocalAabbMax() {
        return m_localAabbMax;
    }

    @Override
    public String getName() {
        return "TRIANGLEMESH";
    }
}
