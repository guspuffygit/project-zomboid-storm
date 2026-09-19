// Port of BulletDynamics/ConstraintSolver/btGeneric6DofConstraint.cpp (Bullet 2.82) --
// btTranslationalLimitMotor
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btTranslationalLimitMotor {
    public final btVector3 m_lowerLimit = new btVector3(); // !< the constraint lower limits
    public final btVector3 m_upperLimit = new btVector3(); // !< the constraint upper limits
    public final btVector3 m_accumulatedImpulse = new btVector3();
    public double m_limitSoftness; // !< Softness for linear limit
    public double m_damping; // !< Damping for linear limit
    public double m_restitution; // ! Bounce parameter for linear limit
    public final btVector3 m_normalCFM = new btVector3(); // !< Constraint force mixing factor
    public final btVector3 m_stopERP = new btVector3(); // !< Error tolerance factor at limit
    public final btVector3 m_stopCFM =
            new btVector3(); // !< Constraint force mixing factor at limit
    public final boolean[] m_enableMotor = new boolean[3];
    public final btVector3 m_targetVelocity = new btVector3(); // !< target motor velocity
    public final btVector3 m_maxMotorForce = new btVector3(); // !< max force on motor
    public final btVector3 m_currentLimitError =
            new btVector3(); // !  How much is violated this limit
    public final btVector3 m_currentLinearDiff = new btVector3(); // !  Current relative offset
    public final int[] m_currentLimit = new int[3]; // !< 0=free, 1=at lower limit, 2=at upper limit

    public btTranslationalLimitMotor() {
        m_lowerLimit.setValue((double) 0.f, (double) 0.f, (double) 0.f);
        m_upperLimit.setValue((double) 0.f, (double) 0.f, (double) 0.f);
        m_accumulatedImpulse.setValue((double) 0.f, (double) 0.f, (double) 0.f);
        m_normalCFM.setValue((double) 0.f, (double) 0.f, (double) 0.f);
        m_stopERP.setValue((double) 0.2f, (double) 0.2f, (double) 0.2f);
        m_stopCFM.setValue((double) 0.f, (double) 0.f, (double) 0.f);

        m_limitSoftness = (double) 0.7f;
        m_damping = (double) 1.0f;
        m_restitution = (double) 0.5f;
        for (int i = 0; i < 3; i++) {
            m_enableMotor[i] = false;
            m_targetVelocity.set(i, (double) 0.f);
            m_maxMotorForce.set(i, (double) 0.f);
        }
    }

    /**
     * C++ copy constructor. Upstream leaves m_currentLimitError, m_currentLinearDiff and
     * m_currentLimit indeterminate; here they are 0.
     */
    public btTranslationalLimitMotor(btTranslationalLimitMotor other) {
        m_lowerLimit.set(other.m_lowerLimit);
        m_upperLimit.set(other.m_upperLimit);
        m_accumulatedImpulse.set(other.m_accumulatedImpulse);

        m_limitSoftness = other.m_limitSoftness;
        m_damping = other.m_damping;
        m_restitution = other.m_restitution;
        m_normalCFM.set(other.m_normalCFM);
        m_stopERP.set(other.m_stopERP);
        m_stopCFM.set(other.m_stopCFM);

        for (int i = 0; i < 3; i++) {
            m_enableMotor[i] = other.m_enableMotor[i];
            m_targetVelocity.set(i, other.m_targetVelocity.get(i));
            m_maxMotorForce.set(i, other.m_maxMotorForce.get(i));
        }
    }

    /**
     * Test limit
     *
     * <p>- free means upper &lt; lower, - locked means upper == lower - limited means upper &gt;
     * lower - limitIndex: first 3 are linear, next 3 are angular
     */
    public boolean isLimited(int limitIndex) {
        return (m_upperLimit.get(limitIndex) >= m_lowerLimit.get(limitIndex));
    }

    public boolean needApplyForce(int limitIndex) {
        if (m_currentLimit[limitIndex] == 0 && m_enableMotor[limitIndex] == false) return false;
        return true;
    }

    public int testLimitValue(int limitIndex, double test_value) {
        double loLimit = m_lowerLimit.get(limitIndex);
        double hiLimit = m_upperLimit.get(limitIndex);
        if (loLimit > hiLimit) {
            m_currentLimit[limitIndex] = 0; // Free from violation
            m_currentLimitError.set(limitIndex, (double) 0.f);
            return 0;
        }

        if (test_value < loLimit) {
            m_currentLimit[limitIndex] = 2; // low limit violation
            m_currentLimitError.set(limitIndex, test_value - loLimit);
            return 2;
        } else if (test_value > hiLimit) {
            m_currentLimit[limitIndex] = 1; // High limit violation
            m_currentLimitError.set(limitIndex, test_value - hiLimit);
            return 1;
        }

        m_currentLimit[limitIndex] = 0; // Free from violation
        m_currentLimitError.set(limitIndex, (double) 0.f);
        return 0;
    }

    public double solveLinearAxis(
            double timeStep,
            double jacDiagABInv,
            btRigidBody body1,
            btVector3 pointInA,
            btRigidBody body2,
            btVector3 pointInB,
            int limit_index,
            btVector3 axis_normal_on_a,
            btVector3 anchorPos) {
        /// find relative velocity
        btVector3 rel_pos1 = anchorPos.sub(body1.getCenterOfMassPosition());
        btVector3 rel_pos2 = anchorPos.sub(body2.getCenterOfMassPosition());

        btVector3 vel1 = body1.getVelocityInLocalPoint(rel_pos1);
        btVector3 vel2 = body2.getVelocityInLocalPoint(rel_pos2);
        btVector3 vel = vel1.sub(vel2);

        double rel_vel = axis_normal_on_a.dot(vel);

        /// apply displacement correction

        // positional error (zeroth order error)
        double depth = -(pointInA.sub(pointInB)).dot(axis_normal_on_a);
        double lo = -btScalar.BT_LARGE_FLOAT;
        double hi = btScalar.BT_LARGE_FLOAT;

        double minLimit = m_lowerLimit.get(limit_index);
        double maxLimit = m_upperLimit.get(limit_index);

        // handle the limits
        if (minLimit < maxLimit) {
            {
                if (depth > maxLimit) {
                    depth -= maxLimit;
                    lo = 0.;

                } else {
                    if (depth < minLimit) {
                        depth -= minLimit;
                        hi = 0.;
                    } else {
                        return (double) 0.0f;
                    }
                }
            }
        }

        double normalImpulse =
                m_limitSoftness
                        * (m_restitution * depth / timeStep - m_damping * rel_vel)
                        * jacDiagABInv;

        double oldNormalImpulse = m_accumulatedImpulse.get(limit_index);
        double sum = oldNormalImpulse + normalImpulse;
        m_accumulatedImpulse.set(limit_index, sum > hi ? 0. : sum < lo ? 0. : sum);
        normalImpulse = m_accumulatedImpulse.get(limit_index) - oldNormalImpulse;

        btVector3 impulse_vector = axis_normal_on_a.mul(normalImpulse);
        body1.applyImpulse(impulse_vector, rel_pos1);
        body2.applyImpulse(impulse_vector.negate(), rel_pos2);

        return normalImpulse;
    }
}
