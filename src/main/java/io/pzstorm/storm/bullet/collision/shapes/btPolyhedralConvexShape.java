// Port of btPolyhedralConvexShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btConvexHullComputer;
import io.pzstorm.storm.bullet.linearmath.btGeometryUtil;
import io.pzstorm.storm.bullet.linearmath.btGrahamScan2dConvexHull;
import io.pzstorm.storm.bullet.linearmath.btGrahamScan2dConvexHull.GrahamVector3;
import io.pzstorm.storm.bullet.linearmath.btIntArray;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btPolyhedralConvexShape extends btConvexInternalShape {
    public btConvexPolyhedron m_polyhedron;

    public btPolyhedralConvexShape() {
        super();
        m_polyhedron = null;
    }

    public boolean initializePolyhedralFeatures() {
        return initializePolyhedralFeatures(0);
    }

    public boolean initializePolyhedralFeatures(int shiftVerticesByMargin) {
        // btAlignedAlloc + placement new -> plain new (old polyhedron is dropped)
        m_polyhedron = new btConvexPolyhedron();

        btAlignedObjectArray<btVector3> orgVertices = btAlignedObjectArray.ofVector3();

        for (int i = 0; i < getNumVertices(); i++) {
            btVector3 newVertex = orgVertices.expand();
            getVertex(i, newVertex);
        }

        btConvexHullComputer conv = new btConvexHullComputer();

        if (shiftVerticesByMargin != 0) {
            btAlignedObjectArray<btVector3> planeEquations = btAlignedObjectArray.ofVector3();
            btGeometryUtil.getPlaneEquationsFromVertices(orgVertices, planeEquations);

            btAlignedObjectArray<btVector3> shiftedPlaneEquations =
                    btAlignedObjectArray.ofVector3();
            for (int p = 0; p < planeEquations.size(); p++) {
                btVector3 plane = new btVector3(planeEquations.get(p));
                plane.set(3, plane.get(3) - getMargin());
                shiftedPlaneEquations.push_back(plane);
            }

            btAlignedObjectArray<btVector3> tmpVertices = btAlignedObjectArray.ofVector3();

            btGeometryUtil.getVerticesFromPlaneEquations(shiftedPlaneEquations, tmpVertices);

            conv.compute(tmpVertices, tmpVertices.size(), 0.0, 0.0);
        } else {
            conv.compute(orgVertices, orgVertices.size(), 0.0, 0.0);
        }

        btAlignedObjectArray<btVector3> faceNormals = btAlignedObjectArray.ofVector3();
        int numFaces = conv.faces.size();
        faceNormals.resize(numFaces);
        btConvexHullComputer convexUtil = conv;

        btAlignedObjectArray<btFace> tmpFaces =
                new btAlignedObjectArray<>(btFace::new, btFace::assign);
        tmpFaces.resize(numFaces);

        int numVertices = convexUtil.vertices.size();
        m_polyhedron.m_vertices.resize(numVertices);
        for (int p = 0; p < numVertices; p++) {
            m_polyhedron.m_vertices.set(p, convexUtil.vertices.get(p));
        }

        for (int i = 0; i < numFaces; i++) {
            int face = convexUtil.faces.get(i);
            btConvexHullComputer.Edge firstEdge = convexUtil.edges.get(face);
            btConvexHullComputer.Edge edge = firstEdge;

            btVector3[] edges = {new btVector3(), new btVector3(), new btVector3()};
            int numEdges = 0;

            do {
                int src = edge.getSourceVertex();
                tmpFaces.get(i).m_indices.push_back(src);
                int targ = edge.getTargetVertex();
                btVector3 wa = new btVector3(convexUtil.vertices.get(src));

                btVector3 wb = new btVector3(convexUtil.vertices.get(targ));
                btVector3 newEdge = wb.sub(wa);
                newEdge.normalize();
                if (numEdges < 2) edges[numEdges++].set(newEdge);

                edge = edge.getNextEdgeOfFace();
            } while (edge != firstEdge);

            double planeEq = (double) 1e30f;

            if (numEdges == 2) {
                faceNormals.get(i).set(edges[0].cross(edges[1]));
                faceNormals.get(i).normalize();
                tmpFaces.get(i).m_plane[0] = faceNormals.get(i).getX();
                tmpFaces.get(i).m_plane[1] = faceNormals.get(i).getY();
                tmpFaces.get(i).m_plane[2] = faceNormals.get(i).getZ();
                tmpFaces.get(i).m_plane[3] = planeEq;
            } else {
                faceNormals.get(i).setZero();
            }

            for (int v = 0; v < tmpFaces.get(i).m_indices.size(); v++) {
                double eq =
                        m_polyhedron
                                .m_vertices
                                .get(tmpFaces.get(i).m_indices.get(v))
                                .dot(faceNormals.get(i));
                if (planeEq > eq) {
                    planeEq = eq;
                }
            }
            tmpFaces.get(i).m_plane[3] = -planeEq;
        }

        // merge coplanar faces and copy them to m_polyhedron

        double faceWeldThreshold = (double) 0.999f;
        btIntArray todoFaces = new btIntArray();
        for (int i = 0; i < tmpFaces.size(); i++) todoFaces.push_back(i);

        while (todoFaces.size() != 0) {
            btIntArray coplanarFaceGroup = new btIntArray();
            int refFace = todoFaces.get(todoFaces.size() - 1);

            coplanarFaceGroup.push_back(refFace);
            btFace faceA = tmpFaces.get(refFace);
            todoFaces.pop_back();

            btVector3 faceNormalA =
                    new btVector3(faceA.m_plane[0], faceA.m_plane[1], faceA.m_plane[2]);
            for (int j = todoFaces.size() - 1; j >= 0; j--) {
                int i = todoFaces.get(j);
                btFace faceB = tmpFaces.get(i);
                btVector3 faceNormalB =
                        new btVector3(faceB.m_plane[0], faceB.m_plane[1], faceB.m_plane[2]);
                if (faceNormalA.dot(faceNormalB) > faceWeldThreshold) {
                    coplanarFaceGroup.push_back(i);
                    todoFaces.remove(i);
                }
            }

            boolean did_merge = false;
            if (coplanarFaceGroup.size() > 1) {
                // do the merge: use Graham Scan 2d convex hull

                btAlignedObjectArray<GrahamVector3> orgpoints = btGrahamScan2dConvexHull.newArray();
                btVector3 averageFaceNormal = new btVector3(0, 0, 0);

                for (int i = 0; i < coplanarFaceGroup.size(); i++) {
                    btFace face = tmpFaces.get(coplanarFaceGroup.get(i));
                    btVector3 faceNormal =
                            new btVector3(face.m_plane[0], face.m_plane[1], face.m_plane[2]);
                    averageFaceNormal.addLocal(faceNormal);
                    for (int f = 0; f < face.m_indices.size(); f++) {
                        int orgIndex = face.m_indices.get(f);
                        btVector3 pt = new btVector3(m_polyhedron.m_vertices.get(orgIndex));

                        boolean found = false;

                        for (int i2 = 0; i2 < orgpoints.size(); i2++) {
                            if (orgpoints.get(i2).m_orgIndex == orgIndex) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) orgpoints.push_back(new GrahamVector3(pt, orgIndex));
                    }
                }

                btFace combinedFace = new btFace();
                for (int i = 0; i < 4; i++)
                    combinedFace.m_plane[i] = tmpFaces.get(coplanarFaceGroup.get(0)).m_plane[i];

                btAlignedObjectArray<GrahamVector3> hull = btGrahamScan2dConvexHull.newArray();

                averageFaceNormal.normalize();
                btGrahamScan2dConvexHull.GrahamScanConvexHull2D(orgpoints, hull, averageFaceNormal);

                for (int i = 0; i < hull.size(); i++) {
                    combinedFace.m_indices.push_back(hull.get(i).m_orgIndex);
                    for (int k = 0; k < orgpoints.size(); k++) {
                        if (orgpoints.get(k).m_orgIndex == hull.get(i).m_orgIndex) {
                            orgpoints.get(k).m_orgIndex = -1; // invalidate...
                            break;
                        }
                    }
                }

                // are there rejected vertices?
                boolean reject_merge = false;

                for (int i = 0; i < orgpoints.size(); i++) {
                    if (orgpoints.get(i).m_orgIndex == -1) continue; // this is in the hull...
                    // this vertex is rejected -- is anybody else using this vertex?
                    for (int j = 0; j < tmpFaces.size(); j++) {

                        btFace face = tmpFaces.get(j);
                        // is this a face of the current coplanar group?
                        boolean is_in_current_group = false;
                        for (int k = 0; k < coplanarFaceGroup.size(); k++) {
                            if (coplanarFaceGroup.get(k) == j) {
                                is_in_current_group = true;
                                break;
                            }
                        }
                        if (is_in_current_group) // ignore this face...
                        continue;
                        // does this face use this rejected vertex?
                        for (int v = 0; v < face.m_indices.size(); v++) {
                            if (face.m_indices.get(v) == orgpoints.get(i).m_orgIndex) {
                                // this rejected vertex is used in another face -- reject merge
                                reject_merge = true;
                                break;
                            }
                        }
                        if (reject_merge) break;
                    }
                    if (reject_merge) break;
                }

                if (!reject_merge) {
                    // do this merge!
                    did_merge = true;
                    m_polyhedron.m_faces.push_back(combinedFace);
                }
            }
            if (!did_merge) {
                for (int i = 0; i < coplanarFaceGroup.size(); i++) {
                    btFace face = new btFace().assign(tmpFaces.get(coplanarFaceGroup.get(i)));
                    m_polyhedron.m_faces.push_back(face);
                }
            }
        }

        m_polyhedron.initialize();

        return true;
    }

    public btConvexPolyhedron getConvexPolyhedron() {
        return m_polyhedron;
    }

    @Override
    public btVector3 localGetSupportingVertexWithoutMargin(btVector3 vec0) {
        btVector3 supVec = new btVector3(0, 0, 0);
        int i;
        double maxDot = -btScalar.BT_LARGE_FLOAT;

        btVector3 vec = new btVector3(vec0);
        double lenSqr = vec.length2();
        if (lenSqr < 0.0001) {
            vec.setValue(1, 0, 0);
        } else {
            double rlen = 1.0 / btScalar.btSqrt(lenSqr);
            vec.mulLocal(rlen);
        }

        double[] newDot = new double[1];

        for (int k = 0; k < getNumVertices(); k += 128) {
            btVector3[] temp = newTemp128();
            int inner_count = Math.min(getNumVertices() - k, 128);
            // upstream quirk: getVertex(i, ...) not getVertex(k + i, ...)
            for (i = 0; i < inner_count; i++) getVertex(i, temp[i]);
            i = vec.maxDot(temp, inner_count, newDot);
            if (newDot[0] > maxDot) {
                maxDot = newDot[0];
                supVec.set(temp[i]);
            }
        }

        return supVec;
    }

    private static btVector3[] newTemp128() {
        btVector3[] temp = new btVector3[128];
        for (int n = 0; n < 128; n++) temp[n] = new btVector3();
        return temp;
    }

    @Override
    public void batchedUnitVectorGetSupportingVertexWithoutMargin(
            btVector3[] vectors, btVector3[] supportVerticesOut, int numVectors) {
        int i;

        double[] newDot = new double[1];

        for (i = 0; i < numVectors; i++) {
            supportVerticesOut[i].set(3, -btScalar.BT_LARGE_FLOAT);
        }

        for (int j = 0; j < numVectors; j++) {
            btVector3 vec = vectors[j];

            for (int k = 0; k < getNumVertices(); k += 128) {
                btVector3[] temp = newTemp128();
                int inner_count = Math.min(getNumVertices() - k, 128);
                for (i = 0; i < inner_count; i++) getVertex(i, temp[i]);
                i = vec.maxDot(temp, inner_count, newDot);
                if (newDot[0] > supportVerticesOut[j].get(3)) {
                    supportVerticesOut[j].set(temp[i]);
                    supportVerticesOut[j].set(3, newDot[0]);
                }
            }
        }
    }

    @Override
    public void calculateLocalInertia(double mass, btVector3 inertia) {
        // not yet, return box inertia

        double margin = getMargin();

        btTransform ident = new btTransform();
        ident.setIdentity();
        btVector3 aabbMin = new btVector3(), aabbMax = new btVector3();
        getAabb(ident, aabbMin, aabbMax);
        btVector3 halfExtents = (aabbMax.sub(aabbMin)).mul(0.5);

        double lx = 2.0 * (halfExtents.x() + margin);
        double ly = 2.0 * (halfExtents.y() + margin);
        double lz = 2.0 * (halfExtents.z() + margin);
        double x2 = lx * lx;
        double y2 = ly * ly;
        double z2 = lz * lz;
        double scaledmass = mass * 0.08333333;

        inertia.set((new btVector3(y2 + z2, x2 + z2, x2 + y2)).mul(scaledmass));
    }

    public abstract int getNumVertices();

    public abstract int getNumEdges();

    public abstract void getEdge(int i, btVector3 pa, btVector3 pb);

    public abstract void getVertex(int i, btVector3 vtx);

    public abstract int getNumPlanes();

    public abstract void getPlane(btVector3 planeNormal, btVector3 planeSupport, int i);

    public abstract boolean isInside(btVector3 pt, double tolerance);
}
