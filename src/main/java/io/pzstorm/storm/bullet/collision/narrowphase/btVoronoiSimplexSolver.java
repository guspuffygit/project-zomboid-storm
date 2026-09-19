// Port of BulletCollision/NarrowPhaseCollision/btVoronoiSimplexSolver.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btVoronoiSimplexSolver is an implementation of the closest point distance algorithm from a 1-4
 * points simplex to the origin. Can be used with GJK, as an alternative to Johnson distance
 * algorithm. BT_USE_EQUAL_VERTEX_THRESHOLD and CATCH_DEGENERATE_TETRAHEDRON are defined.
 */
public class btVoronoiSimplexSolver extends btSimplexSolverInterface {
    public static final int VORONOI_SIMPLEX_MAX_VERTS = 5;

    /** {@code #define VORONOI_DEFAULT_EQUAL_VERTEX_THRESHOLD 0.0001f} */
    public static final double VORONOI_DEFAULT_EQUAL_VERTEX_THRESHOLD = (double) 0.0001f;

    private static final int VERTA = 0;
    private static final int VERTB = 1;
    private static final int VERTC = 2;
    private static final int VERTD = 3;

    public int m_numVertices;

    public final btVector3[] m_simplexVectorW = newVecs();
    public final btVector3[] m_simplexPointsP = newVecs();
    public final btVector3[] m_simplexPointsQ = newVecs();

    public final btVector3 m_cachedP1 = new btVector3();
    public final btVector3 m_cachedP2 = new btVector3();
    public final btVector3 m_cachedV = new btVector3();
    public final btVector3 m_lastW = new btVector3();

    public double m_equalVertexThreshold;
    public boolean m_cachedValidClosest;

    public final btSubSimplexClosestResult m_cachedBC = new btSubSimplexClosestResult();

    public boolean m_needsUpdate;

    private static btVector3[] newVecs() {
        btVector3[] a = new btVector3[VORONOI_SIMPLEX_MAX_VERTS];
        for (int i = 0; i < a.length; i++) a[i] = new btVector3();
        return a;
    }

    public btVoronoiSimplexSolver() {
        m_equalVertexThreshold = VORONOI_DEFAULT_EQUAL_VERTEX_THRESHOLD;
    }

    public void removeVertex(int index) {
        m_numVertices--;
        m_simplexVectorW[index].set(m_simplexVectorW[m_numVertices]);
        m_simplexPointsP[index].set(m_simplexPointsP[m_numVertices]);
        m_simplexPointsQ[index].set(m_simplexPointsQ[m_numVertices]);
    }

    public void reduceVertices(btUsageBitfield usedVerts) {
        if ((numVertices() >= 4) && (!usedVerts.usedVertexD)) removeVertex(3);

        if ((numVertices() >= 3) && (!usedVerts.usedVertexC)) removeVertex(2);

        if ((numVertices() >= 2) && (!usedVerts.usedVertexB)) removeVertex(1);

        if ((numVertices() >= 1) && (!usedVerts.usedVertexA)) removeVertex(0);
    }

    /** clear the simplex, remove all the vertices */
    @Override
    public void reset() {
        m_cachedValidClosest = false;
        m_numVertices = 0;
        m_needsUpdate = true;
        m_lastW.set(
                new btVector3(
                        btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT));
        m_cachedBC.reset();
    }

    /** add a vertex */
    @Override
    public void addVertex(btVector3 w, btVector3 p, btVector3 q) {
        m_lastW.set(w);
        m_needsUpdate = true;

        m_simplexVectorW[m_numVertices].set(w);
        m_simplexPointsP[m_numVertices].set(p);
        m_simplexPointsQ[m_numVertices].set(q);

        m_numVertices++;
    }

    public void setEqualVertexThreshold(double threshold) {
        m_equalVertexThreshold = threshold;
    }

    public double getEqualVertexThreshold() {
        return m_equalVertexThreshold;
    }

    public boolean updateClosestVectorAndPoints() {
        if (m_needsUpdate) {
            m_cachedBC.reset();

            m_needsUpdate = false;

            switch (numVertices()) {
                case 0:
                    m_cachedValidClosest = false;
                    break;
                case 1:
                    {
                        m_cachedP1.set(m_simplexPointsP[0]);
                        m_cachedP2.set(m_simplexPointsQ[0]);
                        m_cachedV.set(m_cachedP1.sub(m_cachedP2)); // == m_simplexVectorW[0]
                        m_cachedBC.reset();
                        m_cachedBC.setBarycentricCoordinates(1.0, 0.0, 0.0, 0.0);
                        m_cachedValidClosest = m_cachedBC.isValid();
                        break;
                    }
                case 2:
                    {
                        // closest point origin from line segment
                        btVector3 from = m_simplexVectorW[0];
                        btVector3 to = m_simplexVectorW[1];
                        btVector3 nearest = new btVector3();

                        btVector3 p = new btVector3(0.0, 0.0, 0.0);
                        btVector3 diff = p.sub(from);
                        btVector3 v = to.sub(from);
                        double t = v.dot(diff);

                        if (t > 0) {
                            double dotVV = v.dot(v);
                            if (t < dotVV) {
                                t /= dotVV;
                                diff.subLocal(v.mul(t));
                                m_cachedBC.m_usedVertices.usedVertexA = true;
                                m_cachedBC.m_usedVertices.usedVertexB = true;
                            } else {
                                t = 1;
                                diff.subLocal(v);
                                // reduce to 1 point
                                m_cachedBC.m_usedVertices.usedVertexB = true;
                            }
                        } else {
                            t = 0;
                            // reduce to 1 point
                            m_cachedBC.m_usedVertices.usedVertexA = true;
                        }
                        m_cachedBC.setBarycentricCoordinates(1 - t, t);
                        nearest.set(from.add(v.mul(t)));

                        m_cachedP1.set(
                                m_simplexPointsP[0].add(
                                        m_simplexPointsP[1].sub(m_simplexPointsP[0]).mul(t)));
                        m_cachedP2.set(
                                m_simplexPointsQ[0].add(
                                        m_simplexPointsQ[1].sub(m_simplexPointsQ[0]).mul(t)));
                        m_cachedV.set(m_cachedP1.sub(m_cachedP2));

                        reduceVertices(m_cachedBC.m_usedVertices);

                        m_cachedValidClosest = m_cachedBC.isValid();
                        break;
                    }
                case 3:
                    {
                        // closest point origin from triangle
                        btVector3 p = new btVector3(0.0, 0.0, 0.0);

                        btVector3 a = m_simplexVectorW[0];
                        btVector3 b = m_simplexVectorW[1];
                        btVector3 c = m_simplexVectorW[2];

                        closestPtPointTriangle(p, a, b, c, m_cachedBC);
                        m_cachedP1.set(
                                m_simplexPointsP[0]
                                        .mul(m_cachedBC.m_barycentricCoords[0])
                                        .add(
                                                m_simplexPointsP[1].mul(
                                                        m_cachedBC.m_barycentricCoords[1]))
                                        .add(
                                                m_simplexPointsP[2].mul(
                                                        m_cachedBC.m_barycentricCoords[2])));

                        m_cachedP2.set(
                                m_simplexPointsQ[0]
                                        .mul(m_cachedBC.m_barycentricCoords[0])
                                        .add(
                                                m_simplexPointsQ[1].mul(
                                                        m_cachedBC.m_barycentricCoords[1]))
                                        .add(
                                                m_simplexPointsQ[2].mul(
                                                        m_cachedBC.m_barycentricCoords[2])));

                        m_cachedV.set(m_cachedP1.sub(m_cachedP2));

                        reduceVertices(m_cachedBC.m_usedVertices);
                        m_cachedValidClosest = m_cachedBC.isValid();

                        break;
                    }
                case 4:
                    {
                        btVector3 p = new btVector3(0.0, 0.0, 0.0);

                        btVector3 a = m_simplexVectorW[0];
                        btVector3 b = m_simplexVectorW[1];
                        btVector3 c = m_simplexVectorW[2];
                        btVector3 d = m_simplexVectorW[3];

                        boolean hasSeperation =
                                closestPtPointTetrahedron(p, a, b, c, d, m_cachedBC);

                        if (hasSeperation) {
                            m_cachedP1.set(
                                    m_simplexPointsP[0]
                                            .mul(m_cachedBC.m_barycentricCoords[0])
                                            .add(
                                                    m_simplexPointsP[1].mul(
                                                            m_cachedBC.m_barycentricCoords[1]))
                                            .add(
                                                    m_simplexPointsP[2].mul(
                                                            m_cachedBC.m_barycentricCoords[2]))
                                            .add(
                                                    m_simplexPointsP[3].mul(
                                                            m_cachedBC.m_barycentricCoords[3])));

                            m_cachedP2.set(
                                    m_simplexPointsQ[0]
                                            .mul(m_cachedBC.m_barycentricCoords[0])
                                            .add(
                                                    m_simplexPointsQ[1].mul(
                                                            m_cachedBC.m_barycentricCoords[1]))
                                            .add(
                                                    m_simplexPointsQ[2].mul(
                                                            m_cachedBC.m_barycentricCoords[2]))
                                            .add(
                                                    m_simplexPointsQ[3].mul(
                                                            m_cachedBC.m_barycentricCoords[3])));

                            m_cachedV.set(m_cachedP1.sub(m_cachedP2));
                            reduceVertices(m_cachedBC.m_usedVertices);
                        } else {
                            if (m_cachedBC.m_degenerate) {
                                m_cachedValidClosest = false;
                            } else {
                                m_cachedValidClosest = true;
                                // degenerate case == false, penetration = true + zero
                                m_cachedV.setValue(0.0, 0.0, 0.0);
                            }
                            break;
                        }

                        m_cachedValidClosest = m_cachedBC.isValid();

                        // closest point origin from tetrahedron
                        break;
                    }
                default:
                    {
                        m_cachedValidClosest = false;
                    }
            }
        }

        return m_cachedValidClosest;
    }

    /** return/calculate the closest vertex */
    @Override
    public boolean closest(btVector3 v) {
        boolean succes = updateClosestVectorAndPoints();
        v.set(m_cachedV);
        return succes;
    }

    @Override
    public double maxVertex() {
        int i, numverts = numVertices();
        double maxV = 0.0;
        for (i = 0; i < numverts; i++) {
            double curLen2 = m_simplexVectorW[i].length2();
            if (maxV < curLen2) maxV = curLen2;
        }
        return maxV;
    }

    @Override
    public boolean fullSimplex() {
        return (m_numVertices == 4);
    }

    /** return the current simplex */
    @Override
    public int getSimplex(btVector3[] pBuf, btVector3[] qBuf, btVector3[] yBuf) {
        int i;
        for (i = 0; i < numVertices(); i++) {
            yBuf[i].set(m_simplexVectorW[i]);
            pBuf[i].set(m_simplexPointsP[i]);
            qBuf[i].set(m_simplexPointsQ[i]);
        }
        return numVertices();
    }

    @Override
    public boolean inSimplex(btVector3 w) {
        boolean found = false;
        int i, numverts = numVertices();

        // w is in the current (reduced) simplex
        for (i = 0; i < numverts; i++) {
            if (m_simplexVectorW[i].distance2(w) <= m_equalVertexThreshold) found = true;
        }

        // check in case lastW is already removed
        if (w.equalsValue(m_lastW)) return true;

        return found;
    }

    @Override
    public void backup_closest(btVector3 v) {
        v.set(m_cachedV);
    }

    @Override
    public boolean emptySimplex() {
        return (numVertices() == 0);
    }

    @Override
    public void compute_points(btVector3 p1, btVector3 p2) {
        updateClosestVectorAndPoints();
        p1.set(m_cachedP1);
        p2.set(m_cachedP2);
    }

    @Override
    public int numVertices() {
        return m_numVertices;
    }

    public boolean closestPtPointTriangle(
            btVector3 p, btVector3 a, btVector3 b, btVector3 c, btSubSimplexClosestResult result) {
        result.m_usedVertices.reset();

        // Check if P in vertex region outside A
        btVector3 ab = b.sub(a);
        btVector3 ac = c.sub(a);
        btVector3 ap = p.sub(a);
        double d1 = ab.dot(ap);
        double d2 = ac.dot(ap);
        if (d1 <= 0.0 && d2 <= 0.0) {
            result.m_closestPointOnSimplex.set(a);
            result.m_usedVertices.usedVertexA = true;
            result.setBarycentricCoordinates(1, 0, 0);
            return true; // a; // barycentric coordinates (1,0,0)
        }

        // Check if P in vertex region outside B
        btVector3 bp = p.sub(b);
        double d3 = ab.dot(bp);
        double d4 = ac.dot(bp);
        if (d3 >= 0.0 && d4 <= d3) {
            result.m_closestPointOnSimplex.set(b);
            result.m_usedVertices.usedVertexB = true;
            result.setBarycentricCoordinates(0, 1, 0);

            return true; // b; // barycentric coordinates (0,1,0)
        }
        // Check if P in edge region of AB, if so return projection of P onto AB
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
            double v = d1 / (d1 - d3);
            result.m_closestPointOnSimplex.set(a.add(ab.mul(v)));
            result.m_usedVertices.usedVertexA = true;
            result.m_usedVertices.usedVertexB = true;
            result.setBarycentricCoordinates(1 - v, v, 0);
            return true;
        }

        // Check if P in vertex region outside C
        btVector3 cp = p.sub(c);
        double d5 = ab.dot(cp);
        double d6 = ac.dot(cp);
        if (d6 >= 0.0 && d5 <= d6) {
            result.m_closestPointOnSimplex.set(c);
            result.m_usedVertices.usedVertexC = true;
            result.setBarycentricCoordinates(0, 0, 1);
            return true; // c; // barycentric coordinates (0,0,1)
        }

        // Check if P in edge region of AC, if so return projection of P onto AC
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
            double w = d2 / (d2 - d6);
            result.m_closestPointOnSimplex.set(a.add(ac.mul(w)));
            result.m_usedVertices.usedVertexA = true;
            result.m_usedVertices.usedVertexC = true;
            result.setBarycentricCoordinates(1 - w, 0, w);
            return true;
        }

        // Check if P in edge region of BC, if so return projection of P onto BC
        double va = d3 * d6 - d5 * d4;
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));

            result.m_closestPointOnSimplex.set(b.add(c.sub(b).mul(w)));
            result.m_usedVertices.usedVertexB = true;
            result.m_usedVertices.usedVertexC = true;
            result.setBarycentricCoordinates(0, 1 - w, w);
            return true;
        }

        // P inside face region. Compute Q through its barycentric coordinates (u,v,w)
        double denom = 1.0 / (va + vb + vc);
        double v = vb * denom;
        double w = vc * denom;

        result.m_closestPointOnSimplex.set(a.add(ab.mul(v)).add(ac.mul(w)));
        result.m_usedVertices.usedVertexA = true;
        result.m_usedVertices.usedVertexB = true;
        result.m_usedVertices.usedVertexC = true;
        result.setBarycentricCoordinates(1 - v - w, v, w);

        return true;
    }

    /** Test if point p and d lie on opposite sides of plane through abc */
    public int pointOutsideOfPlane(
            btVector3 p, btVector3 a, btVector3 b, btVector3 c, btVector3 d) {
        btVector3 normal = (b.sub(a)).cross(c.sub(a));

        double signp = (p.sub(a)).dot(normal); // [AP AB AC]
        double signd = (d.sub(a)).dot(normal); // [AD AB AC]

        // CATCH_DEGENERATE_TETRAHEDRON, BT_USE_DOUBLE_PRECISION
        if (signd * signd < (1e-8 * 1e-8)) {
            return -1;
        }
        // Points on opposite sides if expression signs are opposite
        return signp * signd < 0.0 ? 1 : 0;
    }

    public boolean closestPtPointTetrahedron(
            btVector3 p,
            btVector3 a,
            btVector3 b,
            btVector3 c,
            btVector3 d,
            btSubSimplexClosestResult finalResult) {
        btSubSimplexClosestResult tempResult = new btSubSimplexClosestResult();

        // Start out assuming point inside all halfspaces, so closest to itself
        finalResult.m_closestPointOnSimplex.set(p);
        finalResult.m_usedVertices.reset();
        finalResult.m_usedVertices.usedVertexA = true;
        finalResult.m_usedVertices.usedVertexB = true;
        finalResult.m_usedVertices.usedVertexC = true;
        finalResult.m_usedVertices.usedVertexD = true;

        int pointOutsideABC = pointOutsideOfPlane(p, a, b, c, d);
        int pointOutsideACD = pointOutsideOfPlane(p, a, c, d, b);
        int pointOutsideADB = pointOutsideOfPlane(p, a, d, b, c);
        int pointOutsideBDC = pointOutsideOfPlane(p, b, d, c, a);

        if (pointOutsideABC < 0
                || pointOutsideACD < 0
                || pointOutsideADB < 0
                || pointOutsideBDC < 0) {
            finalResult.m_degenerate = true;
            return false;
        }

        if (pointOutsideABC == 0
                && pointOutsideACD == 0
                && pointOutsideADB == 0
                && pointOutsideBDC == 0) {
            return false;
        }

        double bestSqDist = (double) Float.MAX_VALUE;
        // If point outside face abc then compute closest point on abc
        if (pointOutsideABC != 0) {
            closestPtPointTriangle(p, a, b, c, tempResult);
            btVector3 q = new btVector3(tempResult.m_closestPointOnSimplex);

            double sqDist = (q.sub(p)).dot(q.sub(p));
            // Update best closest point if (squared) distance is less than current best
            if (sqDist < bestSqDist) {
                bestSqDist = sqDist;
                finalResult.m_closestPointOnSimplex.set(q);
                // convert result bitmask!
                finalResult.m_usedVertices.reset();
                finalResult.m_usedVertices.usedVertexA = tempResult.m_usedVertices.usedVertexA;
                finalResult.m_usedVertices.usedVertexB = tempResult.m_usedVertices.usedVertexB;
                finalResult.m_usedVertices.usedVertexC = tempResult.m_usedVertices.usedVertexC;
                finalResult.setBarycentricCoordinates(
                        tempResult.m_barycentricCoords[VERTA],
                        tempResult.m_barycentricCoords[VERTB],
                        tempResult.m_barycentricCoords[VERTC],
                        0);
            }
        }

        // Repeat test for face acd
        if (pointOutsideACD != 0) {
            closestPtPointTriangle(p, a, c, d, tempResult);
            btVector3 q = new btVector3(tempResult.m_closestPointOnSimplex);
            // convert result bitmask!

            double sqDist = (q.sub(p)).dot(q.sub(p));
            if (sqDist < bestSqDist) {
                bestSqDist = sqDist;
                finalResult.m_closestPointOnSimplex.set(q);
                finalResult.m_usedVertices.reset();
                finalResult.m_usedVertices.usedVertexA = tempResult.m_usedVertices.usedVertexA;

                finalResult.m_usedVertices.usedVertexC = tempResult.m_usedVertices.usedVertexB;
                finalResult.m_usedVertices.usedVertexD = tempResult.m_usedVertices.usedVertexC;
                finalResult.setBarycentricCoordinates(
                        tempResult.m_barycentricCoords[VERTA],
                        0,
                        tempResult.m_barycentricCoords[VERTB],
                        tempResult.m_barycentricCoords[VERTC]);
            }
        }
        // Repeat test for face adb

        if (pointOutsideADB != 0) {
            closestPtPointTriangle(p, a, d, b, tempResult);
            btVector3 q = new btVector3(tempResult.m_closestPointOnSimplex);
            // convert result bitmask!

            double sqDist = (q.sub(p)).dot(q.sub(p));
            if (sqDist < bestSqDist) {
                bestSqDist = sqDist;
                finalResult.m_closestPointOnSimplex.set(q);
                finalResult.m_usedVertices.reset();
                finalResult.m_usedVertices.usedVertexA = tempResult.m_usedVertices.usedVertexA;
                finalResult.m_usedVertices.usedVertexB = tempResult.m_usedVertices.usedVertexC;

                finalResult.m_usedVertices.usedVertexD = tempResult.m_usedVertices.usedVertexB;
                finalResult.setBarycentricCoordinates(
                        tempResult.m_barycentricCoords[VERTA],
                        tempResult.m_barycentricCoords[VERTC],
                        0,
                        tempResult.m_barycentricCoords[VERTB]);
            }
        }
        // Repeat test for face bdc

        if (pointOutsideBDC != 0) {
            closestPtPointTriangle(p, b, d, c, tempResult);
            btVector3 q = new btVector3(tempResult.m_closestPointOnSimplex);
            // convert result bitmask!
            double sqDist = (q.sub(p)).dot(q.sub(p));
            if (sqDist < bestSqDist) {
                bestSqDist = sqDist;
                finalResult.m_closestPointOnSimplex.set(q);
                finalResult.m_usedVertices.reset();

                finalResult.m_usedVertices.usedVertexB = tempResult.m_usedVertices.usedVertexA;
                finalResult.m_usedVertices.usedVertexC = tempResult.m_usedVertices.usedVertexC;
                finalResult.m_usedVertices.usedVertexD = tempResult.m_usedVertices.usedVertexB;

                finalResult.setBarycentricCoordinates(
                        0,
                        tempResult.m_barycentricCoords[VERTA],
                        tempResult.m_barycentricCoords[VERTC],
                        tempResult.m_barycentricCoords[VERTB]);
            }
        }

        // help! we ended up full !
        return true;
    }
}
