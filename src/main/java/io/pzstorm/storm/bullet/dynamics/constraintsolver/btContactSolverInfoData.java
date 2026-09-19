// Port of BulletDynamics/ConstraintSolver/btContactSolverInfo.h (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

/**
 * struct btContactSolverInfoData (+ enum btSolverMode). Fields are uninitialised in C++ (0 here).
 */
public class btContactSolverInfoData {
    // enum btSolverMode
    public static final int SOLVER_RANDMIZE_ORDER = 1;
    public static final int SOLVER_FRICTION_SEPARATE = 2;
    public static final int SOLVER_USE_WARMSTARTING = 4;
    public static final int SOLVER_USE_2_FRICTION_DIRECTIONS = 16;
    public static final int SOLVER_ENABLE_FRICTION_DIRECTION_CACHING = 32;
    public static final int SOLVER_DISABLE_VELOCITY_DEPENDENT_FRICTION_DIRECTION = 64;
    public static final int SOLVER_CACHE_FRIENDLY = 128;
    public static final int SOLVER_SIMD = 256;
    public static final int SOLVER_INTERLEAVE_CONTACT_AND_FRICTION_CONSTRAINTS = 512;
    public static final int SOLVER_ALLOW_ZERO_LENGTH_FRICTION_DIRECTIONS = 1024;

    public double m_tau;
    public double m_damping;
    public double m_friction;
    public double m_timeStep;
    public double m_restitution;
    public int m_numIterations;
    public double m_maxErrorReduction;
    public double m_sor;
    public double m_erp;
    public double m_erp2;
    public double m_globalCfm;
    public int m_splitImpulse;
    public double m_splitImpulsePenetrationThreshold;
    public double m_splitImpulseTurnErp;
    public double m_linearSlop;
    public double m_warmstartingFactor;
    public int m_solverMode;
    public int m_restingContactRestitutionThreshold;
    public int m_minimumSolverBatchSize;
    public double m_maxGyroscopicForce;
    public double m_singleAxisRollingFrictionThreshold;

    public btContactSolverInfoData() {}

    /** implicit copy-assignment */
    public btContactSolverInfoData set(btContactSolverInfoData o) {
        m_tau = o.m_tau;
        m_damping = o.m_damping;
        m_friction = o.m_friction;
        m_timeStep = o.m_timeStep;
        m_restitution = o.m_restitution;
        m_numIterations = o.m_numIterations;
        m_maxErrorReduction = o.m_maxErrorReduction;
        m_sor = o.m_sor;
        m_erp = o.m_erp;
        m_erp2 = o.m_erp2;
        m_globalCfm = o.m_globalCfm;
        m_splitImpulse = o.m_splitImpulse;
        m_splitImpulsePenetrationThreshold = o.m_splitImpulsePenetrationThreshold;
        m_splitImpulseTurnErp = o.m_splitImpulseTurnErp;
        m_linearSlop = o.m_linearSlop;
        m_warmstartingFactor = o.m_warmstartingFactor;
        m_solverMode = o.m_solverMode;
        m_restingContactRestitutionThreshold = o.m_restingContactRestitutionThreshold;
        m_minimumSolverBatchSize = o.m_minimumSolverBatchSize;
        m_maxGyroscopicForce = o.m_maxGyroscopicForce;
        m_singleAxisRollingFrictionThreshold = o.m_singleAxisRollingFrictionThreshold;
        return this;
    }
}
