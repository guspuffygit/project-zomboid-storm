// Port of BulletCollision/NarrowPhaseCollision/btSubSimplexConvexCast.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btSubsimplexConvexCast implements Gino van den Bergens' paper "Ray Casting against General Convex
 * Objects with Application to Continuous Collision Detection". GJK based Ray Cast, optimized
 * version.
 */
public class btSubsimplexConvexCast extends btConvexCast {
    /** BT_USE_DOUBLE_PRECISION */
    private static final int MAX_ITERATIONS = 64;

    public btSimplexSolverInterface m_simplexSolver;
    public btConvexShape m_convexA;
    public btConvexShape m_convexB;

    public btSubsimplexConvexCast(
            btConvexShape convexA, btConvexShape convexB, btSimplexSolverInterface simplexSolver) {
        m_simplexSolver = simplexSolver;
        m_convexA = convexA;
        m_convexB = convexB;
    }

    @Override
    public boolean calcTimeOfImpact(
            btTransform fromA,
            btTransform toA,
            btTransform fromB,
            btTransform toB,
            CastResult result) {
        m_simplexSolver.reset();

        btVector3 linVelA = new btVector3(), linVelB = new btVector3();
        linVelA.set(toA.getOrigin().sub(fromA.getOrigin()));
        linVelB.set(toB.getOrigin().sub(fromB.getOrigin()));

        double lambda = 0.0;

        btTransform interpolatedTransA = new btTransform(fromA);
        btTransform interpolatedTransB = new btTransform(fromB);

        // take relative motion
        btVector3 r = (linVelA.sub(linVelB));
        btVector3 v = new btVector3();

        btVector3 supVertexA =
                fromA.transform(
                        m_convexA.localGetSupportingVertex(
                                btMatrix3x3.mul(r.negate(), fromA.getBasis())));
        btVector3 supVertexB =
                fromB.transform(
                        m_convexB.localGetSupportingVertex(btMatrix3x3.mul(r, fromB.getBasis())));
        v.set(supVertexA.sub(supVertexB));
        int maxIter = MAX_ITERATIONS;

        btVector3 n = new btVector3();
        n.setValue(0.0, 0.0, 0.0);
        boolean hasResult = false;
        btVector3 c = new btVector3();

        double lastLambda = lambda;

        double dist2 = v.length2();
        double epsilon = 0.0001;
        btVector3 w = new btVector3(), p = new btVector3();
        double VdotR;

        while ((dist2 > epsilon) && maxIter-- != 0) {
            supVertexA =
                    interpolatedTransA.transform(
                            m_convexA.localGetSupportingVertex(
                                    btMatrix3x3.mul(v.negate(), interpolatedTransA.getBasis())));
            supVertexB =
                    interpolatedTransB.transform(
                            m_convexB.localGetSupportingVertex(
                                    btMatrix3x3.mul(v, interpolatedTransB.getBasis())));
            w.set(supVertexA.sub(supVertexB));

            double VdotW = v.dot(w);

            if (lambda > 1.0) {
                return false;
            }

            if (VdotW > 0.0) {
                VdotR = v.dot(r);

                if (VdotR >= -(btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON)) return false;
                else {
                    lambda -= VdotW / VdotR;
                    // interpolate to next lambda
                    //	x = s + lambda * r;
                    interpolatedTransA
                            .getOrigin()
                            .setInterpolate3(fromA.getOrigin(), toA.getOrigin(), lambda);
                    interpolatedTransB
                            .getOrigin()
                            .setInterpolate3(fromB.getOrigin(), toB.getOrigin(), lambda);
                    // check next line
                    w.set(supVertexA.sub(supVertexB));
                    lastLambda = lambda;
                    n.set(v);
                    hasResult = true;
                }
            }
            // Just like regular GJK only add the vertex if it isn't already (close) to current
            // vertex, it would lead to divisions by zero and NaN etc.
            if (!m_simplexSolver.inSimplex(w)) m_simplexSolver.addVertex(w, supVertexA, supVertexB);

            if (m_simplexSolver.closest(v)) {
                dist2 = v.length2();
                hasResult = true;
                // todo: check this normal for validity
            } else {
                dist2 = 0.0;
            }
        }

        // don't report a time of impact when moving 'away' from the hitnormal

        result.m_fraction = lambda;
        if (n.length2() >= (btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON))
            result.m_normal.set(n.normalized());
        else result.m_normal.set(new btVector3(0.0, 0.0, 0.0));

        // don't report time of impact for motion away from the contact normal (or causes minor
        // penetration)
        if (result.m_normal.dot(r) >= -result.m_allowedPenetration) return false;

        btVector3 hitA = new btVector3(), hitB = new btVector3();
        m_simplexSolver.compute_points(hitA, hitB);
        result.m_hitPoint.set(hitB);
        return true;
    }
}
