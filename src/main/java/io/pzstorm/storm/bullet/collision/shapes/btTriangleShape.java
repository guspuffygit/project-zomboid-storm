// Port of btTriangleShape.h (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btTriangleShape extends btPolyhedralConvexShape {
    public final btVector3[] m_vertices1 = {new btVector3(), new btVector3(), new btVector3()};

    @Override
    public int getNumVertices() {
        return 3;
    }

    public btVector3 getVertexPtr(int index) {
        return m_vertices1[index];
    }

    @Override
    public void getVertex(int index, btVector3 vert) {
        vert.set(m_vertices1[index]);
    }

    @Override
    public int getNumEdges() {
        return 3;
    }

    @Override
    public void getEdge(int i, btVector3 pa, btVector3 pb) {
        getVertex(i, pa);
        getVertex((i + 1) % 3, pb);
    }

    @Override
    public void getAabb(btTransform t, btVector3 aabbMin, btVector3 aabbMax) {
        getAabbSlow(t, aabbMin, aabbMax);
    }

    @Override
    public btVector3 localGetSupportingVertexWithoutMargin(btVector3 dir) {
        btVector3 dots = dir.dot3(m_vertices1[0], m_vertices1[1], m_vertices1[2]);
        return new btVector3(m_vertices1[dots.maxAxis()]);
    }

    @Override
    public void batchedUnitVectorGetSupportingVertexWithoutMargin(
            btVector3[] vectors, btVector3[] supportVerticesOut, int numVectors) {
        for (int i = 0; i < numVectors; i++) {
            btVector3 dir = vectors[i];
            btVector3 dots = dir.dot3(m_vertices1[0], m_vertices1[1], m_vertices1[2]);
            supportVerticesOut[i].set(m_vertices1[dots.maxAxis()]);
        }
    }

    public btTriangleShape() {
        super();
        m_shapeType = BroadphaseNativeTypes.TRIANGLE_SHAPE_PROXYTYPE;
    }

    public btTriangleShape(btVector3 p0, btVector3 p1, btVector3 p2) {
        super();
        m_shapeType = BroadphaseNativeTypes.TRIANGLE_SHAPE_PROXYTYPE;
        m_vertices1[0].set(p0);
        m_vertices1[1].set(p1);
        m_vertices1[2].set(p2);
    }

    @Override
    public void getPlane(btVector3 planeNormal, btVector3 planeSupport, int i) {
        getPlaneEquation(i, planeNormal, planeSupport);
    }

    @Override
    public int getNumPlanes() {
        return 1;
    }

    public void calcNormal(btVector3 normal) {
        normal.set((m_vertices1[1].sub(m_vertices1[0])).cross(m_vertices1[2].sub(m_vertices1[0])));
        normal.normalize();
    }

    public void getPlaneEquation(int i, btVector3 planeNormal, btVector3 planeSupport) {
        calcNormal(planeNormal);
        planeSupport.set(m_vertices1[0]);
    }

    @Override
    public void calculateLocalInertia(double mass, btVector3 inertia) {
        inertia.setValue(0., 0., 0.);
    }

    @Override
    public boolean isInside(btVector3 pt, double tolerance) {
        btVector3 normal = new btVector3();
        calcNormal(normal);
        // distance to plane
        double dist = pt.dot(normal);
        double planeconst = m_vertices1[0].dot(normal);
        dist -= planeconst;
        if (dist >= -tolerance && dist <= tolerance) {
            // inside check on edge-planes
            int i;
            for (i = 0; i < 3; i++) {
                btVector3 pa = new btVector3(), pb = new btVector3();
                getEdge(i, pa, pb);
                btVector3 edge = pb.sub(pa);
                btVector3 edgeNormal = edge.cross(normal);
                edgeNormal.normalize();
                double dist2 = pt.dot(edgeNormal);
                double edgeConst = pa.dot(edgeNormal);
                dist2 -= edgeConst;
                if (dist2 < -tolerance) return false;
            }

            return true;
        }

        return false;
    }

    @Override
    public String getName() {
        return "Triangle";
    }

    @Override
    public int getNumPreferredPenetrationDirections() {
        return 2;
    }

    @Override
    public void getPreferredPenetrationDirection(int index, btVector3 penetrationVector) {
        calcNormal(penetrationVector);
        if (index != 0) penetrationVector.mulLocal(-1.);
    }
}
