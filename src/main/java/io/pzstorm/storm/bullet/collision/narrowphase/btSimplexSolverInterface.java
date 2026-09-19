// Port of BulletCollision/NarrowPhaseCollision/btSimplexSolverInterface.h (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * In 2.82 NO_VIRTUAL_INTERFACE is defined, so {@code btSimplexSolverInterface} is a {@code #define}
 * alias for {@link btVoronoiSimplexSolver}. It is modelled as an abstract base with the single
 * implementation btVoronoiSimplexSolver, so signatures can keep the name the source uses. All calls
 * resolve to btVoronoiSimplexSolver, exactly as in the binary.
 */
public abstract class btSimplexSolverInterface {
    public abstract void reset();

    public abstract void addVertex(btVector3 w, btVector3 p, btVector3 q);

    public abstract boolean closest(btVector3 v);

    public abstract double maxVertex();

    public abstract boolean fullSimplex();

    public abstract int getSimplex(btVector3[] pBuf, btVector3[] qBuf, btVector3[] yBuf);

    public abstract boolean inSimplex(btVector3 w);

    public abstract void backup_closest(btVector3 v);

    public abstract boolean emptySimplex();

    public abstract void compute_points(btVector3 p1, btVector3 p2);

    public abstract int numVertices();
}
