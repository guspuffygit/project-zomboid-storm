// Port of BulletDynamics/ConstraintSolver/btSolverConstraint.h (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * struct btSolverConstraint: 1D constraint along a normal axis between bodyA and bodyB. USE_SIMD is
 * off, so btSimdScalar is btScalar.
 *
 * <p>C++ layout (sizeof 288 = 36 btScalars, {@link #ROWSKIP}) is exposed through {@link
 * #getSlot}/{@link #setSlot} so that {@link btScalarPtr} can emulate the {@code btScalar*} pointer
 * arithmetic of btTypedConstraint::btConstraintInfo2 (rowskip = 36). Slots: 0-3
 * m_relpos1CrossNormal, 4-7 m_contactNormal1, 8-11 m_relpos2CrossNormal, 12-15 m_contactNormal2,
 * 16-19 m_angularComponentA, 20-23 m_angularComponentB, 24 m_appliedPushImpulse, 25
 * m_appliedImpulse, 26 m_friction, 27 m_jacDiagABInv, 28 m_rhs, 29 m_cfm, 30 m_lowerLimit, 31
 * m_upperLimit, 32 m_rhsPenetration, 33 union (m_originalContactPoint / m_numRowsForNonContact
 * Constraint), 34 m_overrideNumSolverIterations+m_frictionIndex, 35 m_solverBodyIdA+B.
 */
public class btSolverConstraint {
    /** sizeof(btSolverConstraint)/sizeof(btScalar) */
    public static final int ROWSKIP = 36;

    // enum btSolverConstraintType
    public static final int BT_SOLVER_CONTACT_1D = 0;
    public static final int BT_SOLVER_FRICTION_1D = 1;

    public final btVector3 m_relpos1CrossNormal = new btVector3();
    public final btVector3 m_contactNormal1 = new btVector3();

    public final btVector3 m_relpos2CrossNormal = new btVector3();
    public final btVector3 m_contactNormal2 =
            new btVector3(); // usually m_contactNormal2 == -m_contactNormal1, but not always

    public final btVector3 m_angularComponentA = new btVector3();
    public final btVector3 m_angularComponentB = new btVector3();

    public double m_appliedPushImpulse;
    public double m_appliedImpulse;

    public double m_friction;
    public double m_jacDiagABInv;
    public double m_rhs;
    public double m_cfm;

    public double m_lowerLimit;
    public double m_upperLimit;
    public double m_rhsPenetration;

    // union { void* m_originalContactPoint; btScalar m_unusedPadding4; int
    // m_numRowsForNonContactConstraint; }
    /** union member {@code void* m_originalContactPoint} (a btManifoldPoint in practice). */
    public Object m_originalContactPoint;

    /** union member {@code int m_numRowsForNonContactConstraint}. */
    public int m_numRowsForNonContactConstraint;

    public int m_overrideNumSolverIterations;
    public int m_frictionIndex;
    public int m_solverBodyIdA;
    public int m_solverBodyIdB;

    public btSolverConstraint() {}

    public btSolverConstraint(btSolverConstraint other) {
        set(other);
    }

    /** implicit copy assignment */
    public btSolverConstraint set(btSolverConstraint o) {
        m_relpos1CrossNormal.set(o.m_relpos1CrossNormal);
        m_contactNormal1.set(o.m_contactNormal1);
        m_relpos2CrossNormal.set(o.m_relpos2CrossNormal);
        m_contactNormal2.set(o.m_contactNormal2);
        m_angularComponentA.set(o.m_angularComponentA);
        m_angularComponentB.set(o.m_angularComponentB);
        m_appliedPushImpulse = o.m_appliedPushImpulse;
        m_appliedImpulse = o.m_appliedImpulse;
        m_friction = o.m_friction;
        m_jacDiagABInv = o.m_jacDiagABInv;
        m_rhs = o.m_rhs;
        m_cfm = o.m_cfm;
        m_lowerLimit = o.m_lowerLimit;
        m_upperLimit = o.m_upperLimit;
        m_rhsPenetration = o.m_rhsPenetration;
        m_originalContactPoint = o.m_originalContactPoint;
        m_numRowsForNonContactConstraint = o.m_numRowsForNonContactConstraint;
        m_overrideNumSolverIterations = o.m_overrideNumSolverIterations;
        m_frictionIndex = o.m_frictionIndex;
        m_solverBodyIdA = o.m_solverBodyIdA;
        m_solverBodyIdB = o.m_solverBodyIdB;
        return this;
    }

    /** {@code memset(this, 0, sizeof(btSolverConstraint))} */
    public void zero() {
        m_relpos1CrossNormal.x =
                m_relpos1CrossNormal.y = m_relpos1CrossNormal.z = m_relpos1CrossNormal.w = 0;
        m_contactNormal1.x = m_contactNormal1.y = m_contactNormal1.z = m_contactNormal1.w = 0;
        m_relpos2CrossNormal.x =
                m_relpos2CrossNormal.y = m_relpos2CrossNormal.z = m_relpos2CrossNormal.w = 0;
        m_contactNormal2.x = m_contactNormal2.y = m_contactNormal2.z = m_contactNormal2.w = 0;
        m_angularComponentA.x =
                m_angularComponentA.y = m_angularComponentA.z = m_angularComponentA.w = 0;
        m_angularComponentB.x =
                m_angularComponentB.y = m_angularComponentB.z = m_angularComponentB.w = 0;
        m_appliedPushImpulse = 0;
        m_appliedImpulse = 0;
        m_friction = 0;
        m_jacDiagABInv = 0;
        m_rhs = 0;
        m_cfm = 0;
        m_lowerLimit = 0;
        m_upperLimit = 0;
        m_rhsPenetration = 0;
        m_originalContactPoint = null;
        m_numRowsForNonContactConstraint = 0;
        m_overrideNumSolverIterations = 0;
        m_frictionIndex = 0;
        m_solverBodyIdA = 0;
        m_solverBodyIdB = 0;
    }

    /** Read btScalar slot {@code ((btScalar*)this)[slot]}; only the btScalar members (0..32). */
    public double getSlot(int slot) {
        switch (slot) {
            case 0:
                return m_relpos1CrossNormal.x;
            case 1:
                return m_relpos1CrossNormal.y;
            case 2:
                return m_relpos1CrossNormal.z;
            case 3:
                return m_relpos1CrossNormal.w;
            case 4:
                return m_contactNormal1.x;
            case 5:
                return m_contactNormal1.y;
            case 6:
                return m_contactNormal1.z;
            case 7:
                return m_contactNormal1.w;
            case 8:
                return m_relpos2CrossNormal.x;
            case 9:
                return m_relpos2CrossNormal.y;
            case 10:
                return m_relpos2CrossNormal.z;
            case 11:
                return m_relpos2CrossNormal.w;
            case 12:
                return m_contactNormal2.x;
            case 13:
                return m_contactNormal2.y;
            case 14:
                return m_contactNormal2.z;
            case 15:
                return m_contactNormal2.w;
            case 16:
                return m_angularComponentA.x;
            case 17:
                return m_angularComponentA.y;
            case 18:
                return m_angularComponentA.z;
            case 19:
                return m_angularComponentA.w;
            case 20:
                return m_angularComponentB.x;
            case 21:
                return m_angularComponentB.y;
            case 22:
                return m_angularComponentB.z;
            case 23:
                return m_angularComponentB.w;
            case 24:
                return m_appliedPushImpulse;
            case 25:
                return m_appliedImpulse;
            case 26:
                return m_friction;
            case 27:
                return m_jacDiagABInv;
            case 28:
                return m_rhs;
            case 29:
                return m_cfm;
            case 30:
                return m_lowerLimit;
            case 31:
                return m_upperLimit;
            case 32:
                return m_rhsPenetration;
            default:
                throw new IllegalArgumentException("btSolverConstraint: non-scalar slot " + slot);
        }
    }

    /**
     * Write btScalar slot {@code ((btScalar*)this)[slot] = v}; only the btScalar members (0..32).
     */
    public void setSlot(int slot, double v) {
        switch (slot) {
            case 0:
                m_relpos1CrossNormal.x = v;
                return;
            case 1:
                m_relpos1CrossNormal.y = v;
                return;
            case 2:
                m_relpos1CrossNormal.z = v;
                return;
            case 3:
                m_relpos1CrossNormal.w = v;
                return;
            case 4:
                m_contactNormal1.x = v;
                return;
            case 5:
                m_contactNormal1.y = v;
                return;
            case 6:
                m_contactNormal1.z = v;
                return;
            case 7:
                m_contactNormal1.w = v;
                return;
            case 8:
                m_relpos2CrossNormal.x = v;
                return;
            case 9:
                m_relpos2CrossNormal.y = v;
                return;
            case 10:
                m_relpos2CrossNormal.z = v;
                return;
            case 11:
                m_relpos2CrossNormal.w = v;
                return;
            case 12:
                m_contactNormal2.x = v;
                return;
            case 13:
                m_contactNormal2.y = v;
                return;
            case 14:
                m_contactNormal2.z = v;
                return;
            case 15:
                m_contactNormal2.w = v;
                return;
            case 16:
                m_angularComponentA.x = v;
                return;
            case 17:
                m_angularComponentA.y = v;
                return;
            case 18:
                m_angularComponentA.z = v;
                return;
            case 19:
                m_angularComponentA.w = v;
                return;
            case 20:
                m_angularComponentB.x = v;
                return;
            case 21:
                m_angularComponentB.y = v;
                return;
            case 22:
                m_angularComponentB.z = v;
                return;
            case 23:
                m_angularComponentB.w = v;
                return;
            case 24:
                m_appliedPushImpulse = v;
                return;
            case 25:
                m_appliedImpulse = v;
                return;
            case 26:
                m_friction = v;
                return;
            case 27:
                m_jacDiagABInv = v;
                return;
            case 28:
                m_rhs = v;
                return;
            case 29:
                m_cfm = v;
                return;
            case 30:
                m_lowerLimit = v;
                return;
            case 31:
                m_upperLimit = v;
                return;
            case 32:
                m_rhsPenetration = v;
                return;
            default:
                throw new IllegalArgumentException("btSolverConstraint: non-scalar slot " + slot);
        }
    }
}
