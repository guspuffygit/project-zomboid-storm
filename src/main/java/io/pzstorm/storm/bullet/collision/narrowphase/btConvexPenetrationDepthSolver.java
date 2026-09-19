// Port of BulletCollision/NarrowPhaseCollision/btConvexPenetrationDepthSolver.h (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** ConvexPenetrationDepthSolver provides an interface for penetration depth calculation. */
public abstract class btConvexPenetrationDepthSolver {
    /** {@code v}, {@code pa}, {@code pb} are out-params (btVector3&), written in place. */
    public abstract boolean calcPenDepth(
            btSimplexSolverInterface simplexSolver,
            btConvexShape convexA,
            btConvexShape convexB,
            btTransform transA,
            btTransform transB,
            btVector3 v,
            btVector3 pa,
            btVector3 pb,
            btIDebugDraw debugDraw);
}
