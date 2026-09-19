// Port of btConvexPolyhedron.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btConvexPolyhedron {
    public final btAlignedObjectArray<btVector3> m_vertices = btAlignedObjectArray.ofVector3();
    public final btAlignedObjectArray<btFace> m_faces =
            new btAlignedObjectArray<>(btFace::new, btFace::assign);
    public final btAlignedObjectArray<btVector3> m_uniqueEdges = btAlignedObjectArray.ofVector3();

    public final btVector3 m_localCenter = new btVector3();
    public final btVector3 m_extents = new btVector3();
    public double m_radius;
    public final btVector3 mC = new btVector3();
    public final btVector3 mE = new btVector3();

    public btConvexPolyhedron() {}

    /** {@code inline bool IsAlmostZero(const btVector3& v)}: fabsf() narrows each component. */
    static boolean IsAlmostZero(btVector3 v) {
        if (Math.abs((float) v.x()) > 1e-6
                || Math.abs((float) v.y()) > 1e-6
                || Math.abs((float) v.z()) > 1e-6) return false;
        return true;
    }

    public boolean testContainment() {
        for (int p = 0; p < 8; p++) {
            btVector3 LocalPt = new btVector3();
            if (p == 0)
                LocalPt.set(
                        m_localCenter.add(
                                new btVector3(
                                        m_extents.get(0), m_extents.get(1), m_extents.get(2))));
            else if (p == 1)
                LocalPt.set(
                        m_localCenter.add(
                                new btVector3(
                                        m_extents.get(0), m_extents.get(1), -m_extents.get(2))));
            else if (p == 2)
                LocalPt.set(
                        m_localCenter.add(
                                new btVector3(
                                        m_extents.get(0), -m_extents.get(1), m_extents.get(2))));
            else if (p == 3)
                LocalPt.set(
                        m_localCenter.add(
                                new btVector3(
                                        m_extents.get(0), -m_extents.get(1), -m_extents.get(2))));
            else if (p == 4)
                LocalPt.set(
                        m_localCenter.add(
                                new btVector3(
                                        -m_extents.get(0), m_extents.get(1), m_extents.get(2))));
            else if (p == 5)
                LocalPt.set(
                        m_localCenter.add(
                                new btVector3(
                                        -m_extents.get(0), m_extents.get(1), -m_extents.get(2))));
            else if (p == 6)
                LocalPt.set(
                        m_localCenter.add(
                                new btVector3(
                                        -m_extents.get(0), -m_extents.get(1), m_extents.get(2))));
            else if (p == 7)
                LocalPt.set(
                        m_localCenter.add(
                                new btVector3(
                                        -m_extents.get(0), -m_extents.get(1), -m_extents.get(2))));

            for (int i = 0; i < m_faces.size(); i++) {
                btVector3 Normal =
                        new btVector3(
                                m_faces.get(i).m_plane[0],
                                m_faces.get(i).m_plane[1],
                                m_faces.get(i).m_plane[2]);
                double d = LocalPt.dot(Normal) + m_faces.get(i).m_plane[3];
                if (d > 0.0) return false;
            }
        }
        return true;
    }

    /**
     * The C++ also fills a {@code btHashMap<btInternalVertexPair, btInternalEdge> edges} whose
     * contents are never read (USE_CONNECTED_FACES is off), so it is omitted. The vertex pair's
     * short-cast + swap is kept because it decides the unique-edge direction.
     */
    public void initialize() {
        double TotalArea = 0.0;

        m_localCenter.setValue(0, 0, 0);
        for (int i = 0; i < m_faces.size(); i++) {
            int numVertices = m_faces.get(i).m_indices.size();
            int NbTris = numVertices;
            for (int j = 0; j < NbTris; j++) {
                int k = (j + 1) % numVertices;
                // btInternalVertexPair vp(indices[j], indices[k])
                short vp_m_v0 = (short) m_faces.get(i).m_indices.get(j);
                short vp_m_v1 = (short) m_faces.get(i).m_indices.get(k);
                if (vp_m_v1 > vp_m_v0) {
                    short tmp = vp_m_v0;
                    vp_m_v0 = vp_m_v1;
                    vp_m_v1 = tmp;
                }
                btVector3 edge = m_vertices.get(vp_m_v1).sub(m_vertices.get(vp_m_v0));
                edge.normalize();

                boolean found = false;

                for (int p = 0; p < m_uniqueEdges.size(); p++) {
                    if (IsAlmostZero(m_uniqueEdges.get(p).sub(edge))
                            || IsAlmostZero(m_uniqueEdges.get(p).add(edge))) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    m_uniqueEdges.push_back(edge);
                }
            }
        }

        for (int i = 0; i < m_faces.size(); i++) {
            int numVertices = m_faces.get(i).m_indices.size();
            int NbTris = numVertices - 2;

            btVector3 p0 = m_vertices.get(m_faces.get(i).m_indices.get(0));
            for (int j = 1; j <= NbTris; j++) {
                int k = (j + 1) % numVertices;
                btVector3 p1 = m_vertices.get(m_faces.get(i).m_indices.get(j));
                btVector3 p2 = m_vertices.get(m_faces.get(i).m_indices.get(k));
                double Area = ((p0.sub(p1)).cross(p0.sub(p2))).length() * 0.5;
                btVector3 Center = (p0.add(p1).add(p2)).div(3.0);
                m_localCenter.addLocal(Center.mul(Area));
                TotalArea += Area;
            }
        }
        m_localCenter.divLocal(TotalArea);

        {
            m_radius = (double) Float.MAX_VALUE;
            for (int i = 0; i < m_faces.size(); i++) {
                btVector3 Normal =
                        new btVector3(
                                m_faces.get(i).m_plane[0],
                                m_faces.get(i).m_plane[1],
                                m_faces.get(i).m_plane[2]);
                double dist =
                        btScalar.btFabs(m_localCenter.dot(Normal) + m_faces.get(i).m_plane[3]);
                if (dist < m_radius) m_radius = dist;
            }

            double MinX = (double) Float.MAX_VALUE;
            double MinY = (double) Float.MAX_VALUE;
            double MinZ = (double) Float.MAX_VALUE;
            double MaxX = -(double) Float.MAX_VALUE;
            double MaxY = -(double) Float.MAX_VALUE;
            double MaxZ = -(double) Float.MAX_VALUE;
            for (int i = 0; i < m_vertices.size(); i++) {
                btVector3 pt = m_vertices.get(i);
                if (pt.x() < MinX) MinX = pt.x();
                if (pt.x() > MaxX) MaxX = pt.x();
                if (pt.y() < MinY) MinY = pt.y();
                if (pt.y() > MaxY) MaxY = pt.y();
                if (pt.z() < MinZ) MinZ = pt.z();
                if (pt.z() > MaxZ) MaxZ = pt.z();
            }
            mC.setValue(MaxX + MinX, MaxY + MinY, MaxZ + MinZ);
            mE.setValue(MaxX - MinX, MaxY - MinY, MaxZ - MinZ);

            // sqrtf(3.0f): float sqrt, promoted to double
            double r = m_radius / (double) (float) Math.sqrt(3.0f);
            int LargestExtent = mE.maxAxis();
            double Step = (mE.get(LargestExtent) * 0.5 - r) / 1024.0;
            m_extents.set(2, r);
            m_extents.set(1, r);
            m_extents.set(0, r);
            m_extents.set(LargestExtent, mE.get(LargestExtent) * 0.5);
            boolean FoundBox = false;
            for (int j = 0; j < 1024; j++) {
                if (testContainment()) {
                    FoundBox = true;
                    break;
                }

                m_extents.set(LargestExtent, m_extents.get(LargestExtent) - Step);
            }
            if (!FoundBox) {
                m_extents.set(2, r);
                m_extents.set(1, r);
                m_extents.set(0, r);
            } else {
                // Refine the box
                double Step2 = (m_radius - r) / 1024.0;
                int e0 = (1 << LargestExtent) & 3;
                int e1 = (1 << e0) & 3;

                for (int j = 0; j < 1024; j++) {
                    double Saved0 = m_extents.get(e0);
                    double Saved1 = m_extents.get(e1);
                    m_extents.set(e0, m_extents.get(e0) + Step2);
                    m_extents.set(e1, m_extents.get(e1) + Step2);

                    if (!testContainment()) {
                        m_extents.set(e0, Saved0);
                        m_extents.set(e1, Saved1);
                        break;
                    }
                }
            }
        }
    }

    /**
     * {@code project(trans, dir, btScalar& minProj, btScalar& maxProj, btVector3& witnesPtMin,
     * btVector3& witnesPtMax)}; scalar outputs in minProj[0]/maxProj[0].
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
        int numVerts = m_vertices.size();
        for (int i = 0; i < numVerts; i++) {
            btVector3 pt = trans.transform(m_vertices.get(i));
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
}
