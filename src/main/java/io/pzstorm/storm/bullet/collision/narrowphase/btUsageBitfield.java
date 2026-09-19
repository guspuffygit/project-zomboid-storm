// Port of BulletCollision/NarrowPhaseCollision/btVoronoiSimplexSolver.h struct btUsageBitfield
// (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

/**
 * The 1-bit {@code unsigned short} bitfields are modelled as booleans (unused1..4 are never read).
 */
public class btUsageBitfield {
    public boolean usedVertexA;
    public boolean usedVertexB;
    public boolean usedVertexC;
    public boolean usedVertexD;

    public btUsageBitfield() {
        reset();
    }

    public void reset() {
        usedVertexA = false;
        usedVertexB = false;
        usedVertexC = false;
        usedVertexD = false;
    }
}
