// Port of BulletDynamics/ConstraintSolver/btConstraintSolver.h (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;

/**
 * btConstraintSolver. Pointer arrays ({@code T**} + count) are passed as a Java array whose index 0
 * is the C++ pointer base (the array may be longer than the count; it may be null when the count is
 * 0). Callers that pass {@code base + offset} in C++ copy the subrange into a fresh array (the
 * solver only reads these arrays).
 */
public abstract class btConstraintSolver {
    // enum btConstraintSolverType
    public static final int BT_SEQUENTIAL_IMPULSE_SOLVER = 1;
    public static final int BT_MLCP_SOLVER = 2;

    /** virtual ~btConstraintSolver() */
    public void destroy() {}

    public void prepareSolve(int numBodies, int numManifolds) {}

    public abstract double solveGroup(
            btCollisionObject[] bodies,
            int numBodies,
            btPersistentManifold[] manifold,
            int numManifolds,
            btTypedConstraint[] constraints,
            int numConstraints,
            btContactSolverInfo info,
            btIDebugDraw debugDrawer,
            btDispatcher dispatcher);

    public void allSolved(btContactSolverInfo info, btIDebugDraw debugDrawer) {}

    public abstract void reset();

    public abstract int getSolverType();
}
