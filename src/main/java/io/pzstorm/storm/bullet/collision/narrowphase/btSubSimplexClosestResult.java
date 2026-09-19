// Port of BulletCollision/NarrowPhaseCollision/btVoronoiSimplexSolver.h struct
// btSubSimplexClosestResult (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btSubSimplexClosestResult {
    public final btVector3 m_closestPointOnSimplex = new btVector3();
    // MASK for m_usedVertices
    // stores the simplex vertex-usage, using the MASK,
    // if m_usedVertices & MASK then the related vertex is used
    public final btUsageBitfield m_usedVertices = new btUsageBitfield();
    public final double[] m_barycentricCoords = new double[4];
    public boolean m_degenerate;

    public void reset() {
        m_degenerate = false;
        setBarycentricCoordinates();
        m_usedVertices.reset();
    }

    public boolean isValid() {
        boolean valid =
                (m_barycentricCoords[0] >= 0.0)
                        && (m_barycentricCoords[1] >= 0.0)
                        && (m_barycentricCoords[2] >= 0.0)
                        && (m_barycentricCoords[3] >= 0.0);
        return valid;
    }

    public void setBarycentricCoordinates() {
        setBarycentricCoordinates(0.0, 0.0, 0.0, 0.0);
    }

    public void setBarycentricCoordinates(double a, double b) {
        setBarycentricCoordinates(a, b, 0.0, 0.0);
    }

    public void setBarycentricCoordinates(double a, double b, double c) {
        setBarycentricCoordinates(a, b, c, 0.0);
    }

    public void setBarycentricCoordinates(double a, double b, double c, double d) {
        m_barycentricCoords[0] = a;
        m_barycentricCoords[1] = b;
        m_barycentricCoords[2] = c;
        m_barycentricCoords[3] = d;
    }
}
