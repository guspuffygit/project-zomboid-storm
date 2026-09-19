// Port of BulletCollision/NarrowPhaseCollision/btContinuousConvexCollision.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btStaticPlaneShape;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btTransformUtil;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btContinuousConvexCollision implements angular and linear time of impact for convex objects.
 * Based on Brian Mirtich's Conservative Advancement idea (PhD thesis).
 */
public class btContinuousConvexCollision extends btConvexCast {
    /**
     * This maximum should not be necessary. It allows for untested/degenerate cases in production
     * code.
     */
    private static final int MAX_ITERATIONS = 64;

    public btSimplexSolverInterface m_simplexSolver;
    public btConvexPenetrationDepthSolver m_penetrationDepthSolver;
    public btConvexShape m_convexA;
    // second object is either a convex or a plane (code sharing)
    public btConvexShape m_convexB1;
    public btStaticPlaneShape m_planeShape;

    public btContinuousConvexCollision(
            btConvexShape convexA,
            btConvexShape convexB,
            btSimplexSolverInterface simplexSolver,
            btConvexPenetrationDepthSolver penetrationDepthSolver) {
        m_simplexSolver = simplexSolver;
        m_penetrationDepthSolver = penetrationDepthSolver;
        m_convexA = convexA;
        m_convexB1 = convexB;
        m_planeShape = null;
    }

    public btContinuousConvexCollision(btConvexShape convexA, btStaticPlaneShape plane) {
        m_simplexSolver = null;
        m_penetrationDepthSolver = null;
        m_convexA = convexA;
        m_convexB1 = null;
        m_planeShape = plane;
    }

    public void computeClosestPoints(
            btTransform transA, btTransform transB, btPointCollector pointCollector) {
        if (m_convexB1 != null) {
            m_simplexSolver.reset();
            btGjkPairDetector gjk =
                    new btGjkPairDetector(
                            m_convexA,
                            m_convexB1,
                            m_convexA.getShapeType(),
                            m_convexB1.getShapeType(),
                            m_convexA.getMargin(),
                            m_convexB1.getMargin(),
                            m_simplexSolver,
                            m_penetrationDepthSolver);
            btGjkPairDetector.ClosestPointInput input = new btGjkPairDetector.ClosestPointInput();
            input.m_transformA.set(transA);
            input.m_transformB.set(transB);
            gjk.getClosestPoints(input, pointCollector, null);
        } else {
            // convex versus plane
            btConvexShape convexShape = m_convexA;
            btStaticPlaneShape planeShape = m_planeShape;

            btVector3 planeNormal = planeShape.getPlaneNormal();
            double planeConstant = planeShape.getPlaneConstant();

            btTransform convexWorldTransform = new btTransform(transA);
            btTransform convexInPlaneTrans = new btTransform();
            convexInPlaneTrans.set(transB.inverse().mul(convexWorldTransform));
            btTransform planeInConvex = new btTransform();
            planeInConvex.set(convexWorldTransform.inverse().mul(transB));

            btVector3 vtx =
                    convexShape.localGetSupportingVertex(
                            planeInConvex.getBasis().mul(planeNormal.negate()));

            btVector3 vtxInPlane = convexInPlaneTrans.transform(vtx);
            double distance = (planeNormal.dot(vtxInPlane) - planeConstant);

            btVector3 vtxInPlaneProjected = vtxInPlane.sub(planeNormal.mul(distance));
            btVector3 vtxInPlaneWorld = transB.mul(vtxInPlaneProjected);
            btVector3 normalOnSurfaceB = transB.getBasis().mul(planeNormal);

            pointCollector.addContactPoint(normalOnSurfaceB, vtxInPlaneWorld, distance);
        }
    }

    @Override
    public boolean calcTimeOfImpact(
            btTransform fromA,
            btTransform toA,
            btTransform fromB,
            btTransform toB,
            CastResult result) {
        // compute linear and angular velocity for this interval, to interpolate
        btVector3 linVelA = new btVector3(),
                angVelA = new btVector3(),
                linVelB = new btVector3(),
                angVelB = new btVector3();
        btTransformUtil.calculateVelocity(fromA, toA, 1.0, linVelA, angVelA);
        btTransformUtil.calculateVelocity(fromB, toB, 1.0, linVelB, angVelB);

        double boundingRadiusA = m_convexA.getAngularMotionDisc();
        double boundingRadiusB =
                m_convexB1 != null ? m_convexB1.getAngularMotionDisc() : (double) 0.f;

        double maxAngularProjectedVelocity =
                angVelA.length() * boundingRadiusA + angVelB.length() * boundingRadiusB;
        btVector3 relLinVel = (linVelB.sub(linVelA));

        double relLinVelocLength = (linVelB.sub(linVelA)).length();

        if ((relLinVelocLength + maxAngularProjectedVelocity) == (double) 0.f) return false;

        double lambda = 0.0;
        btVector3 v = new btVector3(1, 0, 0);

        int maxIter = MAX_ITERATIONS;

        btVector3 n = new btVector3();
        n.setValue(0.0, 0.0, 0.0);
        boolean hasResult = false;
        btVector3 c = new btVector3();

        double lastLambda = lambda;

        int numIter = 0;
        // first solution, using GJK

        double radius = (double) 0.001f;

        btPointCollector pointCollector1 = new btPointCollector();

        {
            computeClosestPoints(fromA, fromB, pointCollector1);

            hasResult = pointCollector1.m_hasResult;
            c.set(pointCollector1.m_pointInWorld);
        }

        if (hasResult) {
            double dist;
            dist = pointCollector1.m_distance + result.m_allowedPenetration;
            n.set(pointCollector1.m_normalOnBInWorld);
            double projectedLinearVelocity = relLinVel.dot(n);
            if ((projectedLinearVelocity + maxAngularProjectedVelocity) <= btScalar.SIMD_EPSILON)
                return false;

            // not close enough
            while (dist > radius) {
                if (result.m_debugDrawer != null) {
                    result.m_debugDrawer.drawSphere(c, (double) 0.2f, new btVector3(1, 1, 1));
                }
                double dLambda = 0.0;

                projectedLinearVelocity = relLinVel.dot(n);

                // don't report time of impact for motion away from the contact normal (or causes
                // minor penetration)
                if ((projectedLinearVelocity + maxAngularProjectedVelocity)
                        <= btScalar.SIMD_EPSILON) return false;

                dLambda = dist / (projectedLinearVelocity + maxAngularProjectedVelocity);

                lambda += dLambda;

                if (lambda > 1.0) return false;

                if (lambda < 0.0) return false;

                // todo: next check with relative epsilon
                if (lambda <= lastLambda) {
                    return false;
                }
                lastLambda = lambda;

                // interpolate to next lambda
                btTransform interpolatedTransA = new btTransform(),
                        interpolatedTransB = new btTransform(),
                        relativeTrans = new btTransform();

                btTransformUtil.integrateTransform(
                        fromA, linVelA, angVelA, lambda, interpolatedTransA);
                btTransformUtil.integrateTransform(
                        fromB, linVelB, angVelB, lambda, interpolatedTransB);
                relativeTrans.set(interpolatedTransB.inverseTimes(interpolatedTransA));

                if (result.m_debugDrawer != null) {
                    result.m_debugDrawer.drawSphere(
                            interpolatedTransA.getOrigin(), (double) 0.2f, new btVector3(1, 0, 0));
                }

                result.DebugDraw(lambda);

                btPointCollector pointCollector = new btPointCollector();
                computeClosestPoints(interpolatedTransA, interpolatedTransB, pointCollector);

                if (pointCollector.m_hasResult) {
                    dist = pointCollector.m_distance + result.m_allowedPenetration;
                    c.set(pointCollector.m_pointInWorld);
                    n.set(pointCollector.m_normalOnBInWorld);
                } else {
                    result.reportFailure(-1, numIter);
                    return false;
                }

                numIter++;
                if (numIter > maxIter) {
                    result.reportFailure(-2, numIter);
                    return false;
                }
            }

            result.m_fraction = lambda;
            result.m_normal.set(n);
            result.m_hitPoint.set(c);
            return true;
        }

        return false;
    }
}
