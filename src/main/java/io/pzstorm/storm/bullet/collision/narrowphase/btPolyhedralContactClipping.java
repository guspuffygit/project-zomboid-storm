// Port of BulletCollision/NarrowPhaseCollision/btPolyhedralContactClipping.{h,cpp} (Bullet 2.82)
// Separating axis test based on work from Pierre Terdiman, contact clipping based on work from
// Simon Hobbs.
// TEST_INTERNAL_OBJECTS is defined (btConvexPolyhedron.h), so the internal-object early-outs are
// compiled in.
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexPolyhedron;
import io.pzstorm.storm.bullet.collision.shapes.btFace;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public final class btPolyhedralContactClipping {
    private btPolyhedralContactClipping() {}

    private static final double FLT_MAX = (double) Float.MAX_VALUE;

    /** {@code typedef btAlignedObjectArray<btVector3> btVertexArray;} (value mode) */
    public static btAlignedObjectArray<btVector3> newVertexArray() {
        return btAlignedObjectArray.ofVector3();
    }

    /** Clips a face to the back of a plane */
    public static void clipFace(
            btAlignedObjectArray<btVector3> pVtxIn,
            btAlignedObjectArray<btVector3> ppVtxOut,
            btVector3 planeNormalWS,
            double planeEqWS) {

        int ve;
        double ds, de;
        int numVerts = pVtxIn.size();
        if (numVerts < 2) return;

        btVector3 firstVertex = new btVector3(pVtxIn.get(pVtxIn.size() - 1));
        btVector3 endVertex = new btVector3(pVtxIn.get(0));

        ds = planeNormalWS.dot(firstVertex) + planeEqWS;

        for (ve = 0; ve < numVerts; ve++) {
            endVertex.set(pVtxIn.get(ve));

            de = planeNormalWS.dot(endVertex) + planeEqWS;

            if (ds < 0) {
                if (de < 0) {
                    // Start < 0, end < 0, so output endVertex
                    ppVtxOut.push_back(new btVector3(endVertex));
                } else {
                    // Start < 0, end >= 0, so output intersection
                    ppVtxOut.push_back(
                            firstVertex.lerp(endVertex, (ds * (double) 1.f / (ds - de))));
                }
            } else {
                if (de < 0) {
                    // Start >= 0, end < 0 so output intersection and end
                    ppVtxOut.push_back(
                            firstVertex.lerp(endVertex, (ds * (double) 1.f / (ds - de))));
                    ppVtxOut.push_back(new btVector3(endVertex));
                }
            }
            firstVertex.set(endVertex);
            ds = de;
        }
    }

    /** {@code depth} is written to depth[0]; witness points in place. */
    static boolean TestSepAxis(
            btConvexPolyhedron hullA,
            btConvexPolyhedron hullB,
            btTransform transA,
            btTransform transB,
            btVector3 sep_axis,
            double[] depth,
            btVector3 witnessPointA,
            btVector3 witnessPointB) {
        double[] Min0 = new double[1], Max0 = new double[1];
        double[] Min1 = new double[1], Max1 = new double[1];
        btVector3 witnesPtMinA = new btVector3(), witnesPtMaxA = new btVector3();
        btVector3 witnesPtMinB = new btVector3(), witnesPtMaxB = new btVector3();

        hullA.project(transA, sep_axis, Min0, Max0, witnesPtMinA, witnesPtMaxA);
        hullB.project(transB, sep_axis, Min1, Max1, witnesPtMinB, witnesPtMaxB);

        if (Max0[0] < Min1[0] || Max1[0] < Min0[0]) return false;

        double d0 = Max0[0] - Min1[0];
        double d1 = Max1[0] - Min0[0];
        if (d0 < d1) {
            depth[0] = d0;
            witnessPointA.set(witnesPtMaxA);
            witnessPointB.set(witnesPtMinB);

        } else {
            depth[0] = d1;
            witnessPointA.set(witnesPtMinA);
            witnessPointB.set(witnesPtMaxB);
        }

        return true;
    }

    /**
     * {@code fabsf(v.x())>1e-6}: the double is converted to float, fabsf'd, and promoted back for
     * the compare.
     */
    static boolean IsAlmostZero(btVector3 v) {
        if ((double) Math.abs((float) v.x) > 1e-6
                || (double) Math.abs((float) v.y) > 1e-6
                || (double) Math.abs((float) v.z) > 1e-6) return false;
        return true;
    }

    static void BoxSupport(double[] extents, double[] sv, double[] p) {
        p[0] = sv[0] < 0.0f ? -extents[0] : extents[0];
        p[1] = sv[1] < 0.0f ? -extents[1] : extents[1];
        p[2] = sv[2] < 0.0f ? -extents[2] : extents[2];
    }

    static void InverseTransformPoint3x3(btVector3 out, btVector3 in, btTransform tr) {
        final btMatrix3x3 rot = tr.getBasis();
        final btVector3 r0 = rot.getRow(0);
        final btVector3 r1 = rot.getRow(1);
        final btVector3 r2 = rot.getRow(2);

        final double x = r0.x * in.x + r1.x * in.y + r2.x * in.z;
        final double y = r0.y * in.x + r1.y * in.y + r2.y * in.z;
        final double z = r0.z * in.x + r1.z * in.y + r2.z * in.z;

        out.setValue(x, y, z);
    }

    static boolean TestInternalObjects(
            btTransform trans0,
            btTransform trans1,
            btVector3 delta_c,
            btVector3 axis,
            btConvexPolyhedron convex0,
            btConvexPolyhedron convex1,
            double dmin) {
        final double dp = delta_c.dot(axis);

        btVector3 localAxis0 = new btVector3();
        InverseTransformPoint3x3(localAxis0, axis, trans0);
        btVector3 localAxis1 = new btVector3();
        InverseTransformPoint3x3(localAxis1, axis, trans1);

        double[] p0 = new double[3];
        BoxSupport(xyz(convex0.m_extents), xyz(localAxis0), p0);
        double[] p1 = new double[3];
        BoxSupport(xyz(convex1.m_extents), xyz(localAxis1), p1);

        final double Radius0 = p0[0] * localAxis0.x + p0[1] * localAxis0.y + p0[2] * localAxis0.z;
        final double Radius1 = p1[0] * localAxis1.x + p1[1] * localAxis1.y + p1[2] * localAxis1.z;

        final double MinRadius = Radius0 > convex0.m_radius ? Radius0 : convex0.m_radius;
        final double MaxRadius = Radius1 > convex1.m_radius ? Radius1 : convex1.m_radius;

        final double MinMaxRadius = MaxRadius + MinRadius;
        final double d0 = MinMaxRadius + dp;
        final double d1 = MinMaxRadius - dp;

        final double depth = d0 < d1 ? d0 : d1;
        if (depth > dmin) return false;
        return true;
    }

    private static double[] xyz(btVector3 v) {
        return new double[] {v.x, v.y, v.z};
    }

    /** Out-params: ptsVector, offsetA, offsetB written in place; tA/tB in tA[0]/tB[0]. */
    static void btSegmentsClosestPoints(
            btVector3 ptsVector,
            btVector3 offsetA,
            btVector3 offsetB,
            double[] tA,
            double[] tB,
            btVector3 translation,
            btVector3 dirA,
            double hlenA,
            btVector3 dirB,
            double hlenB) {
        // compute the parameters of the closest points on each line segment

        double dirA_dot_dirB = btVector3.btDot(dirA, dirB);
        double dirA_dot_trans = btVector3.btDot(dirA, translation);
        double dirB_dot_trans = btVector3.btDot(dirB, translation);

        double denom = 1.0f - dirA_dot_dirB * dirA_dot_dirB;

        if (denom == 0.0f) {
            tA[0] = 0.0f;
        } else {
            tA[0] = (dirA_dot_trans - dirB_dot_trans * dirA_dot_dirB) / denom;
            if (tA[0] < -hlenA) tA[0] = -hlenA;
            else if (tA[0] > hlenA) tA[0] = hlenA;
        }

        tB[0] = tA[0] * dirA_dot_dirB - dirB_dot_trans;

        if (tB[0] < -hlenB) {
            tB[0] = -hlenB;
            tA[0] = tB[0] * dirA_dot_dirB + dirA_dot_trans;

            if (tA[0] < -hlenA) tA[0] = -hlenA;
            else if (tA[0] > hlenA) tA[0] = hlenA;
        } else if (tB[0] > hlenB) {
            tB[0] = hlenB;
            tA[0] = tB[0] * dirA_dot_dirB + dirA_dot_trans;

            if (tA[0] < -hlenA) tA[0] = -hlenA;
            else if (tA[0] > hlenA) tA[0] = hlenA;
        }

        // compute the closest points relative to segment centers.

        offsetA.set(dirA.mul(tA[0]));
        offsetB.set(dirB.mul(tB[0]));

        ptsVector.set(translation.sub(offsetA).add(offsetB));
    }

    public static boolean findSeparatingAxis(
            btConvexPolyhedron hullA,
            btConvexPolyhedron hullB,
            btTransform transA,
            btTransform transB,
            btVector3 sep,
            btDiscreteCollisionDetectorInterface.Result resultOut) {
        btGlobals.gActualSATPairTests++;

        final btVector3 c0 = transA.mul(hullA.m_localCenter);
        final btVector3 c1 = transB.mul(hullB.m_localCenter);
        final btVector3 DeltaC2 = c0.sub(c1);

        double dmin = FLT_MAX;
        int curPlaneTests = 0;

        int numFacesA = hullA.m_faces.size();
        // Test normals from hullA
        for (int i = 0; i < numFacesA; i++) {
            final btFace fA = hullA.m_faces.get(i);
            final btVector3 Normal = new btVector3(fA.m_plane[0], fA.m_plane[1], fA.m_plane[2]);
            btVector3 faceANormalWS = transA.getBasis().mul(Normal);
            if (DeltaC2.dot(faceANormalWS) < 0) faceANormalWS.mulLocal(-1.f);

            curPlaneTests++;
            btGlobals.gExpectedNbTests++;
            if (btGlobals.gUseInternalObject
                    && !TestInternalObjects(
                            transA, transB, DeltaC2, faceANormalWS, hullA, hullB, dmin)) continue;
            btGlobals.gActualNbTests++;

            double[] d = new double[1];
            btVector3 wA = new btVector3(), wB = new btVector3();
            if (!TestSepAxis(hullA, hullB, transA, transB, faceANormalWS, d, wA, wB)) return false;

            if (d[0] < dmin) {
                dmin = d[0];
                sep.set(faceANormalWS);
            }
        }

        int numFacesB = hullB.m_faces.size();
        // Test normals from hullB
        for (int i = 0; i < numFacesB; i++) {
            final btFace fB = hullB.m_faces.get(i);
            final btVector3 Normal = new btVector3(fB.m_plane[0], fB.m_plane[1], fB.m_plane[2]);
            btVector3 WorldNormal = transB.getBasis().mul(Normal);
            if (DeltaC2.dot(WorldNormal) < 0) WorldNormal.mulLocal(-1.f);

            curPlaneTests++;
            btGlobals.gExpectedNbTests++;
            if (btGlobals.gUseInternalObject
                    && !TestInternalObjects(
                            transA, transB, DeltaC2, WorldNormal, hullA, hullB, dmin)) continue;
            btGlobals.gActualNbTests++;

            double[] d = new double[1];
            btVector3 wA = new btVector3(), wB = new btVector3();
            if (!TestSepAxis(hullA, hullB, transA, transB, WorldNormal, d, wA, wB)) return false;

            if (d[0] < dmin) {
                dmin = d[0];
                sep.set(WorldNormal);
            }
        }

        int edgeA = -1;
        int edgeB = -1;
        // uninitialised in C++; only read when edgeA/edgeB >= 0, i.e. after assignment
        btVector3 worldEdgeA = new btVector3();
        btVector3 worldEdgeB = new btVector3();
        btVector3 witnessPointA = new btVector3(), witnessPointB = new btVector3();

        int curEdgeEdge = 0;
        // Test edges
        for (int e0 = 0; e0 < hullA.m_uniqueEdges.size(); e0++) {
            final btVector3 edge0 = new btVector3(hullA.m_uniqueEdges.get(e0));
            final btVector3 WorldEdge0 = transA.getBasis().mul(edge0);
            for (int e1 = 0; e1 < hullB.m_uniqueEdges.size(); e1++) {
                final btVector3 edge1 = new btVector3(hullB.m_uniqueEdges.get(e1));
                final btVector3 WorldEdge1 = transB.getBasis().mul(edge1);

                btVector3 Cross = WorldEdge0.cross(WorldEdge1);
                curEdgeEdge++;
                if (!IsAlmostZero(Cross)) {
                    Cross.normalize();
                    if (DeltaC2.dot(Cross) < 0) Cross.mulLocal(-1.f);

                    btGlobals.gExpectedNbTests++;
                    if (btGlobals.gUseInternalObject
                            && !TestInternalObjects(
                                    transA, transB, DeltaC2, Cross, hullA, hullB, dmin)) continue;
                    btGlobals.gActualNbTests++;

                    double[] dist = new double[1];
                    btVector3 wA = new btVector3(), wB = new btVector3();
                    if (!TestSepAxis(hullA, hullB, transA, transB, Cross, dist, wA, wB))
                        return false;

                    if (dist[0] < dmin) {
                        dmin = dist[0];
                        sep.set(Cross);
                        edgeA = e0;
                        edgeB = e1;
                        worldEdgeA.set(WorldEdge0);
                        worldEdgeB.set(WorldEdge1);
                        witnessPointA.set(wA);
                        witnessPointB.set(wB);
                    }
                }
            }
        }

        if (edgeA >= 0 && edgeB >= 0) {
            // add an edge-edge contact

            btVector3 ptsVector = new btVector3();
            btVector3 offsetA = new btVector3();
            btVector3 offsetB = new btVector3();
            double[] tA = new double[1];
            double[] tB = new double[1];

            btVector3 translation = witnessPointB.sub(witnessPointA);

            btVector3 dirA = new btVector3(worldEdgeA);
            btVector3 dirB = new btVector3(worldEdgeB);

            double hlenB = (double) 1e30f;
            double hlenA = (double) 1e30f;

            btSegmentsClosestPoints(
                    ptsVector, offsetA, offsetB, tA, tB, translation, dirA, hlenA, dirB, hlenB);

            double nlSqrt = ptsVector.length2();
            if (nlSqrt > btScalar.SIMD_EPSILON) {
                double nl = btScalar.btSqrt(nlSqrt);
                ptsVector.mulLocal((double) 1.f / nl);
                if (ptsVector.dot(DeltaC2) < 0.f) {
                    ptsVector.mulLocal(-1.f);
                }
                btVector3 ptOnB = witnessPointB.add(offsetB);
                double distance = nl;
                resultOut.addContactPoint(ptsVector, ptOnB, -distance);
            }
        }

        if ((DeltaC2.dot(sep)) < 0.0f) sep.set(sep.negate());

        return true;
    }

    public static void clipFaceAgainstHull(
            btVector3 separatingNormal,
            btConvexPolyhedron hullA,
            btTransform transA,
            btAlignedObjectArray<btVector3> worldVertsB1,
            final double minDist,
            double maxDist,
            btDiscreteCollisionDetectorInterface.Result resultOut) {
        btAlignedObjectArray<btVector3> worldVertsB2 = newVertexArray();
        btAlignedObjectArray<btVector3> pVtxIn = worldVertsB1;
        btAlignedObjectArray<btVector3> pVtxOut = worldVertsB2;
        pVtxOut.reserve(pVtxIn.size());

        int closestFaceA = -1;
        {
            double dmin = FLT_MAX;
            for (int face = 0; face < hullA.m_faces.size(); face++) {
                final btFace f = hullA.m_faces.get(face);
                final btVector3 Normal = new btVector3(f.m_plane[0], f.m_plane[1], f.m_plane[2]);
                final btVector3 faceANormalWS = transA.getBasis().mul(Normal);

                double d = faceANormalWS.dot(separatingNormal);
                if (d < dmin) {
                    dmin = d;
                    closestFaceA = face;
                }
            }
        }
        if (closestFaceA < 0) return;

        final btFace polyA = hullA.m_faces.get(closestFaceA);

        // clip polygon to back of planes of all faces of hull A that are adjacent to witness face
        int numVerticesA = polyA.m_indices.size();
        for (int e0 = 0; e0 < numVerticesA; e0++) {
            final btVector3 a = hullA.m_vertices.get(polyA.m_indices.get(e0));
            final btVector3 b = hullA.m_vertices.get(polyA.m_indices.get((e0 + 1) % numVerticesA));
            final btVector3 edge0 = a.sub(b);
            final btVector3 WorldEdge0 = transA.getBasis().mul(edge0);
            btVector3 worldPlaneAnormal1 =
                    transA.getBasis()
                            .mul(
                                    new btVector3(
                                            polyA.m_plane[0], polyA.m_plane[1], polyA.m_plane[2]));

            btVector3 planeNormalWS1 = WorldEdge0.cross(worldPlaneAnormal1).negate();
            btVector3 worldA1 = transA.mul(a);
            double planeEqWS1 = -worldA1.dot(planeNormalWS1);

            btVector3 planeNormalWS = planeNormalWS1;
            double planeEqWS = planeEqWS1;

            // clip face

            clipFace(pVtxIn, pVtxOut, planeNormalWS, planeEqWS);
            btAlignedObjectArray<btVector3> tmp = pVtxIn;
            pVtxIn = pVtxOut;
            pVtxOut = tmp;
            pVtxOut.resize(0);
        }

        // only keep points that are behind the witness face
        {
            btVector3 localPlaneNormal =
                    new btVector3(polyA.m_plane[0], polyA.m_plane[1], polyA.m_plane[2]);
            double localPlaneEq = polyA.m_plane[3];
            btVector3 planeNormalWS = transA.getBasis().mul(localPlaneNormal);
            double planeEqWS = localPlaneEq - planeNormalWS.dot(transA.getOrigin());
            for (int i = 0; i < pVtxIn.size(); i++) {
                btVector3 vtx = new btVector3(pVtxIn.at(i));
                double depth = planeNormalWS.dot(vtx) + planeEqWS;
                if (depth <= minDist) {
                    depth = minDist;
                }

                if (depth <= maxDist) {
                    btVector3 point = new btVector3(pVtxIn.at(i));
                    resultOut.addContactPoint(separatingNormal, point, depth);
                }
            }
        }
    }

    public static void clipHullAgainstHull(
            btVector3 separatingNormal1,
            btConvexPolyhedron hullA,
            btConvexPolyhedron hullB,
            btTransform transA,
            btTransform transB,
            final double minDist,
            double maxDist,
            btDiscreteCollisionDetectorInterface.Result resultOut) {

        btVector3 separatingNormal = separatingNormal1.normalized();

        int closestFaceB = -1;
        double dmax = -FLT_MAX;
        {
            for (int face = 0; face < hullB.m_faces.size(); face++) {
                final btFace f = hullB.m_faces.get(face);
                final btVector3 Normal = new btVector3(f.m_plane[0], f.m_plane[1], f.m_plane[2]);
                final btVector3 WorldNormal = transB.getBasis().mul(Normal);
                double d = WorldNormal.dot(separatingNormal);
                if (d > dmax) {
                    dmax = d;
                    closestFaceB = face;
                }
            }
        }
        btAlignedObjectArray<btVector3> worldVertsB1 = newVertexArray();
        // DEVIATION: C++ reads hullB.m_faces[closestFaceB] unconditionally (undefined behaviour
        // when closestFaceB == -1: no faces, or every d NaN / <= -FLT_MAX). The vertices built
        // from it are only consumed when closestFaceB >= 0, so the guard changes no defined result.
        if (closestFaceB >= 0) {
            final btFace polyB = hullB.m_faces.get(closestFaceB);
            final int numVertices = polyB.m_indices.size();
            for (int e0 = 0; e0 < numVertices; e0++) {
                final btVector3 b = hullB.m_vertices.get(polyB.m_indices.get(e0));
                worldVertsB1.push_back(transB.mul(b));
            }
        }

        if (closestFaceB >= 0)
            clipFaceAgainstHull(
                    separatingNormal, hullA, transA, worldVertsB1, minDist, maxDist, resultOut);
    }
}
