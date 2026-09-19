// Port of BulletDynamics/ConstraintSolver/btContactSolverInfo.h (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

/**
 * struct btContactSolverInfo. The serialization structs btContactSolverInfoDoubleData/FloatData are
 * not ported (btSerializer is not linked into the PZ solver path).
 */
public class btContactSolverInfo extends btContactSolverInfoData {
    public btContactSolverInfo() {
        m_tau = 0.6;
        m_damping = 1.0;
        m_friction = 0.3;
        m_timeStep = (double) (1.f / 60.f);
        m_restitution = 0.;
        m_maxErrorReduction = 20.;
        m_numIterations = 10;
        m_erp = 0.2;
        m_erp2 = 0.8;
        m_globalCfm = 0.;
        m_sor = 1.;
        m_splitImpulse = 1; // true
        m_splitImpulsePenetrationThreshold = (double) -.04f;
        m_splitImpulseTurnErp = (double) 0.1f;
        m_linearSlop = 0.0;
        m_warmstartingFactor = 0.85;
        m_solverMode = SOLVER_USE_WARMSTARTING | SOLVER_SIMD;
        m_restingContactRestitutionThreshold = 2;
        m_minimumSolverBatchSize = 128;
        m_maxGyroscopicForce = (double) 100.f;
        m_singleAxisRollingFrictionThreshold = (double) 1e30f;
    }
}
