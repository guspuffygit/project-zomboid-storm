// Port of LinearMath/btGeometryUtil.cpp/.h (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Plane/vertex helpers. {@code btAlignedObjectArray<btVector3>} arguments must be value mode
 * ({@link btAlignedObjectArray#ofVector3()}); plane equations are btVector3 with the constant in
 * {@code [3]} (w). {@code isInside} is declared but never defined in 2.82 and is not ported.
 */
public final class btGeometryUtil {
    private btGeometryUtil() {}

    public static boolean isPointInsidePlanes(
            btAlignedObjectArray<btVector3> planeEquations, btVector3 point, double margin) {
        int numbrushes = planeEquations.size();
        for (int i = 0; i < numbrushes; i++) {
            btVector3 N1 = planeEquations.get(i);
            double dist = N1.dot(point) + N1.get(3) - margin;
            if (dist > 0.0) {
                return false;
            }
        }
        return true;
    }

    public static boolean areVerticesBehindPlane(
            btVector3 planeNormal, btAlignedObjectArray<btVector3> vertices, double margin) {
        int numvertices = vertices.size();
        for (int i = 0; i < numvertices; i++) {
            btVector3 N1 = vertices.get(i);
            double dist = planeNormal.dot(N1) + planeNormal.get(3) - margin;
            if (dist > 0.0) {
                return false;
            }
        }
        return true;
    }

    /** File-static {@code notExist} in btGeometryUtil.cpp. */
    public static boolean notExist(
            btVector3 planeEquation, btAlignedObjectArray<btVector3> planeEquations) {
        int numbrushes = planeEquations.size();
        for (int i = 0; i < numbrushes; i++) {
            btVector3 N1 = planeEquations.get(i);
            if (planeEquation.dot(N1) > 0.999) {
                return false;
            }
        }
        return true;
    }

    public static void getPlaneEquationsFromVertices(
            btAlignedObjectArray<btVector3> vertices,
            btAlignedObjectArray<btVector3> planeEquationsOut) {
        int numvertices = vertices.size();
        // brute force:
        for (int i = 0; i < numvertices; i++) {
            btVector3 N1 = vertices.get(i);

            for (int j = i + 1; j < numvertices; j++) {
                btVector3 N2 = vertices.get(j);

                for (int k = j + 1; k < numvertices; k++) {

                    btVector3 N3 = vertices.get(k);

                    btVector3 planeEquation = new btVector3(), edge0, edge1;
                    edge0 = N2.sub(N1);
                    edge1 = N3.sub(N1);
                    double normalSign = 1.0;
                    for (int ww = 0; ww < 2; ww++) {
                        planeEquation.set(edge0.cross(edge1).mul(normalSign));
                        if (planeEquation.length2() > 0.0001) {
                            planeEquation.normalize();
                            if (notExist(planeEquation, planeEquationsOut)) {
                                planeEquation.set(3, -planeEquation.dot(N1));

                                // check if inside, and replace supportingVertexOut if needed
                                if (areVerticesBehindPlane(planeEquation, vertices, 0.01)) {
                                    planeEquationsOut.push_back(planeEquation);
                                }
                            }
                        }
                        normalSign = -1.0;
                    }
                }
            }
        }
    }

    public static void getVerticesFromPlaneEquations(
            btAlignedObjectArray<btVector3> planeEquations,
            btAlignedObjectArray<btVector3> verticesOut) {
        int numbrushes = planeEquations.size();
        // brute force:
        for (int i = 0; i < numbrushes; i++) {
            btVector3 N1 = planeEquations.get(i);

            for (int j = i + 1; j < numbrushes; j++) {
                btVector3 N2 = planeEquations.get(j);

                for (int k = j + 1; k < numbrushes; k++) {

                    btVector3 N3 = planeEquations.get(k);

                    btVector3 n2n3 = N2.cross(N3);
                    btVector3 n3n1 = N3.cross(N1);
                    btVector3 n1n2 = N1.cross(N2);

                    if ((n2n3.length2() > 0.0001)
                            && (n3n1.length2() > 0.0001)
                            && (n1n2.length2() > 0.0001)) {
                        // point P out of 3 plane equations:
                        double quotient = (N1.dot(n2n3));
                        if (Math.abs(quotient) > 0.000001) {
                            quotient = -1.0 / quotient;
                            n2n3.mulLocal(N1.get(3));
                            n3n1.mulLocal(N2.get(3));
                            n1n2.mulLocal(N3.get(3));
                            btVector3 potentialVertex = new btVector3(n2n3);
                            potentialVertex.addLocal(n3n1);
                            potentialVertex.addLocal(n1n2);
                            potentialVertex.mulLocal(quotient);

                            // check if inside, and replace supportingVertexOut if needed
                            if (isPointInsidePlanes(planeEquations, potentialVertex, 0.01)) {
                                verticesOut.push_back(potentialVertex);
                            }
                        }
                    }
                }
            }
        }
    }
}
