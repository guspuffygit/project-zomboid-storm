// Port of BulletCollision/NarrowPhaseCollision/btGjkConvexCast.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** GjkConvexCast performs a raycast on a convex object using support mapping. */
public class btGjkConvexCast extends btConvexCast {
    /** BT_USE_DOUBLE_PRECISION */
    private static final int MAX_ITERATIONS = 64;

    public btSimplexSolverInterface m_simplexSolver;
    public btConvexShape m_convexA;
    public btConvexShape m_convexB;

    public btGjkConvexCast(
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

        // compute linear velocity for this interval, to interpolate
        // assume no rotation/angular velocity, assert here?
        btVector3 linVelA = new btVector3(), linVelB = new btVector3();
        linVelA.set(toA.getOrigin().sub(fromA.getOrigin()));
        linVelB.set(toB.getOrigin().sub(fromB.getOrigin()));

        double radius = 0.001;
        double lambda = 0.0;
        btVector3 v = new btVector3(1, 0, 0);

        int maxIter = MAX_ITERATIONS;

        btVector3 n = new btVector3();
        n.setValue(0.0, 0.0, 0.0);
        boolean hasResult = false;
        btVector3 c = new btVector3();
        btVector3 r = (linVelA.sub(linVelB));

        double lastLambda = lambda;

        int numIter = 0;
        // first solution, using GJK

        btTransform identityTrans = new btTransform();
        identityTrans.setIdentity();

        btPointCollector pointCollector = new btPointCollector();

        btGjkPairDetector gjk = new btGjkPairDetector(m_convexA, m_convexB, m_simplexSolver, null);
        btGjkPairDetector.ClosestPointInput input = new btGjkPairDetector.ClosestPointInput();

        // we don't use margins during CCD
        input.m_transformA.set(fromA);
        input.m_transformB.set(fromB);
        gjk.getClosestPoints(input, pointCollector, null);

        hasResult = pointCollector.m_hasResult;
        c.set(pointCollector.m_pointInWorld);

        if (hasResult) {
            double dist;
            dist = pointCollector.m_distance;
            n.set(pointCollector.m_normalOnBInWorld);

            // not close enough
            while (dist > radius) {
                numIter++;
                if (numIter > maxIter) {
                    return false; // todo: report a failure
                }
                double dLambda = 0.0;

                double projectedLinearVelocity = r.dot(n);

                dLambda = dist / (projectedLinearVelocity);

                lambda -= dLambda;

                if (lambda > 1.0) return false;

                if (lambda < 0.0) return false;

                // todo: next check with relative epsilon
                if (lambda <= lastLambda) {
                    return false;
                }
                lastLambda = lambda;

                // interpolate to next lambda
                result.DebugDraw(lambda);
                input.m_transformA
                        .getOrigin()
                        .setInterpolate3(fromA.getOrigin(), toA.getOrigin(), lambda);
                input.m_transformB
                        .getOrigin()
                        .setInterpolate3(fromB.getOrigin(), toB.getOrigin(), lambda);

                gjk.getClosestPoints(input, pointCollector, null);
                if (pointCollector.m_hasResult) {
                    if (pointCollector.m_distance < 0.0) {
                        result.m_fraction = lastLambda;
                        n.set(pointCollector.m_normalOnBInWorld);
                        result.m_normal.set(n);
                        result.m_hitPoint.set(pointCollector.m_pointInWorld);
                        return true;
                    }
                    c.set(pointCollector.m_pointInWorld);
                    n.set(pointCollector.m_normalOnBInWorld);
                    dist = pointCollector.m_distance;
                } else {
                    // ??
                    return false;
                }
            }

            // is n normalized?
            // don't report time of impact for motion away from the contact normal (or causes minor
            // penetration)
            if (n.dot(r) >= -result.m_allowedPenetration) return false;

            result.m_fraction = lambda;
            result.m_normal.set(n);
            result.m_hitPoint.set(c);
            return true;
        }

        return false;
    }
}
