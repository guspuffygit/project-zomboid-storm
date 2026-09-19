// Port of BulletCollision/NarrowPhaseCollision/btGjkEpaPenetrationDepthSolver.{h,cpp} (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * EpaPenetrationDepthSolver uses the Expanding Polytope Algorithm to calculate the penetration
 * depth between two convex shapes.
 */
public class btGjkEpaPenetrationDepthSolver extends btConvexPenetrationDepthSolver {
    public btGjkEpaPenetrationDepthSolver() {}

    @Override
    public boolean calcPenDepth(
            btSimplexSolverInterface simplexSolver,
            btConvexShape pConvexA,
            btConvexShape pConvexB,
            btTransform transformA,
            btTransform transformB,
            btVector3 v,
            btVector3 wWitnessOnA,
            btVector3 wWitnessOnB,
            btIDebugDraw debugDraw) {

        btVector3 guessVector = new btVector3(transformB.getOrigin().sub(transformA.getOrigin()));
        btGjkEpaSolver2.sResults results = new btGjkEpaSolver2.sResults();

        if (btGjkEpaSolver2.Penetration(
                pConvexA, transformA, pConvexB, transformB, guessVector, results)) {
            wWitnessOnA.set(results.witnesses[0]);
            wWitnessOnB.set(results.witnesses[1]);
            v.set(results.normal);
            return true;
        } else {
            if (btGjkEpaSolver2.Distance(
                    pConvexA, transformA, pConvexB, transformB, guessVector, results)) {
                wWitnessOnA.set(results.witnesses[0]);
                wWitnessOnB.set(results.witnesses[1]);
                v.set(results.normal);
                return false;
            }
        }

        return false;
    }
}
