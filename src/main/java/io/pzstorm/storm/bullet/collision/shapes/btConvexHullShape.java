// Port of btConvexHullShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btConvexHullShape extends btPolyhedralConvexAabbCachingShape {
    public final btAlignedObjectArray<btVector3> m_unscaledPoints =
            btAlignedObjectArray.ofVector3();

    public btConvexHullShape() {
        this(null, 0, 32);
    }

    /**
     * {@code btConvexHullShape(const btScalar* points=0, int numPoints=0, int
     * stride=sizeof(btVector3))}. {@code stride} is in bytes (sizeof(btVector3) = 32 in double
     * precision); {@code points} is read as btScalar (double) at {@code stride/8} element steps.
     */
    public btConvexHullShape(double[] points, int numPoints, int stride) {
        super();
        m_shapeType = BroadphaseNativeTypes.CONVEX_HULL_SHAPE_PROXYTYPE;
        m_unscaledPoints.resize(numPoints);

        int pointsAddress = 0;
        for (int i = 0; i < numPoints; i++) {
            int point = pointsAddress / 8;
            m_unscaledPoints.set(
                    i, new btVector3(points[point], points[point + 1], points[point + 2]));
            pointsAddress += stride;
        }

        recalcLocalAabb();
    }

    public void addPoint(btVector3 point) {
        addPoint(point, true);
    }

    public void addPoint(btVector3 point, boolean recalculateLocalAabb) {
        m_unscaledPoints.push_back(point);
        if (recalculateLocalAabb) recalcLocalAabb();
    }

    /** {@code &m_unscaledPoints[0]}: an array view holding the live point objects. */
    public btVector3[] getUnscaledPoints() {
        int n = m_unscaledPoints.size();
        btVector3[] pts = new btVector3[n];
        for (int i = 0; i < n; i++) pts[i] = m_unscaledPoints.get(i);
        return pts;
    }

    public btVector3[] getPoints() {
        return getUnscaledPoints();
    }

    public btVector3 getScaledPoint(int i) {
        return m_unscaledPoints.get(i).mul(m_localScaling);
    }

    public int getNumPoints() {
        return m_unscaledPoints.size();
    }

    /** {@code btVector3::maxDot(&m_unscaledPoints[0], size, dotOut)} without building an array. */
    static int maxDot(
            btVector3 self,
            btAlignedObjectArray<btVector3> array,
            int array_count,
            double[] dotOut) {
        double maxDot = -btScalar.SIMD_INFINITY;
        int ptIndex = -1;
        for (int i = 0; i < array_count; i++) {
            double dot = array.get(i).dot(self);
            if (dot > maxDot) {
                maxDot = dot;
                ptIndex = i;
            }
        }
        dotOut[0] = maxDot;
        return ptIndex;
    }

    @Override
    public btVector3 localGetSupportingVertex(btVector3 vec) {
        btVector3 supVertex = localGetSupportingVertexWithoutMargin(vec);

        if (getMargin() != 0.0) {
            btVector3 vecnorm = new btVector3(vec);
            if (vecnorm.length2() < (btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON)) {
                vecnorm.setValue(-1.0, -1.0, -1.0);
            }
            vecnorm.normalize();
            supVertex.addLocal(vecnorm.mul(getMargin()));
        }
        return supVertex;
    }

    @Override
    public btVector3 localGetSupportingVertexWithoutMargin(btVector3 vec) {
        btVector3 supVec = new btVector3(0.0, 0.0, 0.0);
        double[] maxDot = {-btScalar.BT_LARGE_FLOAT};

        if (0 < m_unscaledPoints.size()) {
            btVector3 scaled = vec.mul(m_localScaling);
            int index = maxDot(scaled, m_unscaledPoints, m_unscaledPoints.size(), maxDot);
            return m_unscaledPoints.get(index).mul(m_localScaling);
        }

        return supVec;
    }

    @Override
    public void batchedUnitVectorGetSupportingVertexWithoutMargin(
            btVector3[] vectors, btVector3[] supportVerticesOut, int numVectors) {
        double[] newDot = new double[1];
        {
            for (int i = 0; i < numVectors; i++) {
                supportVerticesOut[i].set(3, -btScalar.BT_LARGE_FLOAT);
            }
        }

        for (int j = 0; j < numVectors; j++) {
            btVector3 vec = vectors[j].mul(m_localScaling); // dot(a*b,c) = dot(a,b*c)
            if (0 < m_unscaledPoints.size()) {
                int i = maxDot(vec, m_unscaledPoints, m_unscaledPoints.size(), newDot);
                supportVerticesOut[j].set(getScaledPoint(i));
                supportVerticesOut[j].set(3, newDot[0]);
            } else supportVerticesOut[j].set(3, -btScalar.BT_LARGE_FLOAT);
        }
    }

    /**
     * {@code project(trans, dir, btScalar& minProj, btScalar& maxProj, btVector3& witnesPtMin,
     * btVector3& witnesPtMax)} (a separate virtual from btConvexShape::project in 2.82).
     */
    public void project(
            btTransform trans,
            btVector3 dir,
            double[] minProj,
            double[] maxProj,
            btVector3 witnesPtMin,
            btVector3 witnesPtMax) {
        minProj[0] = (double) Float.MAX_VALUE;
        maxProj[0] = -(double) Float.MAX_VALUE;

        int numVerts = m_unscaledPoints.size();
        for (int i = 0; i < numVerts; i++) {
            btVector3 vtx = m_unscaledPoints.get(i).mul(m_localScaling);
            btVector3 pt = trans.transform(vtx);
            double dp = pt.dot(dir);
            if (dp < minProj[0]) {
                minProj[0] = dp;
                witnesPtMin.set(pt);
            }
            if (dp > maxProj[0]) {
                maxProj[0] = dp;
                witnesPtMax.set(pt);
            }
        }

        if (minProj[0] > maxProj[0]) {
            double t = minProj[0];
            minProj[0] = maxProj[0];
            maxProj[0] = t;
            btVector3 tv = new btVector3(witnesPtMin);
            witnesPtMin.set(witnesPtMax);
            witnesPtMax.set(tv);
        }
    }

    @Override
    public String getName() {
        return "Convex";
    }

    @Override
    public int getNumVertices() {
        return m_unscaledPoints.size();
    }

    @Override
    public int getNumEdges() {
        return m_unscaledPoints.size();
    }

    @Override
    public void getEdge(int i, btVector3 pa, btVector3 pb) {
        int index0 = i % m_unscaledPoints.size();
        int index1 = (i + 1) % m_unscaledPoints.size();
        pa.set(getScaledPoint(index0));
        pb.set(getScaledPoint(index1));
    }

    @Override
    public void getVertex(int i, btVector3 vtx) {
        vtx.set(getScaledPoint(i));
    }

    @Override
    public int getNumPlanes() {
        return 0;
    }

    @Override
    public void getPlane(btVector3 planeNormal, btVector3 planeSupport, int i) {}

    @Override
    public boolean isInside(btVector3 pt, double tolerance) {
        return false;
    }

    @Override
    public void setLocalScaling(btVector3 scaling) {
        m_localScaling.set(scaling);
        recalcLocalAabb();
    }
}
