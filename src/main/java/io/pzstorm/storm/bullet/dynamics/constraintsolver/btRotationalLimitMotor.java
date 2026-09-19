// Port of BulletDynamics/ConstraintSolver/btGeneric6DofConstraint.cpp (Bullet 2.82) --
// btRotationalLimitMotor
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** Rotation Limit structure for generic joints */
public class btRotationalLimitMotor {
    public double m_loLimit; // !< joint limit
    public double m_hiLimit; // !< joint limit
    public double m_targetVelocity; // !< target motor velocity
    public double m_maxMotorForce; // !< max force on motor
    public double m_maxLimitForce; // !< max force on limit
    public double m_damping; // !< Damping.
    public double m_limitSoftness; // ! Relaxation factor
    public double m_normalCFM; // !< Constraint force mixing factor
    public double m_stopERP; // !< Error tolerance factor when joint is at limit
    public double m_stopCFM; // !< Constraint force mixing factor when joint is at limit
    public double m_bounce; // !< restitution factor
    public boolean m_enableMotor;

    public double m_currentLimitError; // !  How much is violated this limit
    public double m_currentPosition; // !  current value of angle
    public int m_currentLimit; // !< 0=free, 1=at lo limit, 2=at hi limit
    public double m_accumulatedImpulse;

    public btRotationalLimitMotor() {
        m_accumulatedImpulse = (double) 0.f;
        m_targetVelocity = 0;
        m_maxMotorForce = (double) 0.1f;
        m_maxLimitForce = (double) 300.0f;
        m_loLimit = (double) 1.0f;
        m_hiLimit = (double) -1.0f;
        m_normalCFM = (double) 0.f;
        m_stopERP = (double) 0.2f;
        m_stopCFM = (double) 0.f;
        m_bounce = (double) 0.0f;
        m_damping = (double) 1.0f;
        m_limitSoftness = (double) 0.5f;
        m_currentLimit = 0;
        m_currentLimitError = 0;
        m_enableMotor = false;
    }

    /**
     * C++ copy constructor. Upstream does not copy m_maxLimitForce, m_damping, m_currentPosition or
     * m_accumulatedImpulse (left indeterminate); here they are 0.
     */
    public btRotationalLimitMotor(btRotationalLimitMotor limot) {
        m_targetVelocity = limot.m_targetVelocity;
        m_maxMotorForce = limot.m_maxMotorForce;
        m_limitSoftness = limot.m_limitSoftness;
        m_loLimit = limot.m_loLimit;
        m_hiLimit = limot.m_hiLimit;
        m_normalCFM = limot.m_normalCFM;
        m_stopERP = limot.m_stopERP;
        m_stopCFM = limot.m_stopCFM;
        m_bounce = limot.m_bounce;
        m_currentLimit = limot.m_currentLimit;
        m_currentLimitError = limot.m_currentLimitError;
        m_enableMotor = limot.m_enableMotor;
    }

    /** Implicit C++ copy assignment (memberwise). */
    public btRotationalLimitMotor set(btRotationalLimitMotor o) {
        m_loLimit = o.m_loLimit;
        m_hiLimit = o.m_hiLimit;
        m_targetVelocity = o.m_targetVelocity;
        m_maxMotorForce = o.m_maxMotorForce;
        m_maxLimitForce = o.m_maxLimitForce;
        m_damping = o.m_damping;
        m_limitSoftness = o.m_limitSoftness;
        m_normalCFM = o.m_normalCFM;
        m_stopERP = o.m_stopERP;
        m_stopCFM = o.m_stopCFM;
        m_bounce = o.m_bounce;
        m_enableMotor = o.m_enableMotor;
        m_currentLimitError = o.m_currentLimitError;
        m_currentPosition = o.m_currentPosition;
        m_currentLimit = o.m_currentLimit;
        m_accumulatedImpulse = o.m_accumulatedImpulse;
        return this;
    }

    /** Is limited */
    public boolean isLimited() {
        if (m_loLimit > m_hiLimit) return false;
        return true;
    }

    /** Need apply correction */
    public boolean needApplyTorques() {
        if (m_currentLimit == 0 && m_enableMotor == false) return false;
        return true;
    }

    /** calculates m_currentLimit and m_currentLimitError. */
    public int testLimitValue(double test_value) {
        if (m_loLimit > m_hiLimit) {
            m_currentLimit = 0; // Free from violation
            return 0;
        }
        if (test_value < m_loLimit) {
            m_currentLimit = 1; // low limit violation
            m_currentLimitError = test_value - m_loLimit;
            if (m_currentLimitError > btScalar.SIMD_PI) m_currentLimitError -= btScalar.SIMD_2_PI;
            else if (m_currentLimitError < -btScalar.SIMD_PI)
                m_currentLimitError += btScalar.SIMD_2_PI;
            return 1;
        } else if (test_value > m_hiLimit) {
            m_currentLimit = 2; // High limit violation
            m_currentLimitError = test_value - m_hiLimit;
            if (m_currentLimitError > btScalar.SIMD_PI) m_currentLimitError -= btScalar.SIMD_2_PI;
            else if (m_currentLimitError < -btScalar.SIMD_PI)
                m_currentLimitError += btScalar.SIMD_2_PI;
            return 2;
        }

        m_currentLimit = 0; // Free from violation
        return 0;
    }

    /** apply the correction impulses for two bodies */
    public double solveAngularLimits(
            double timeStep,
            btVector3 axis,
            double jacDiagABInv,
            btRigidBody body0,
            btRigidBody body1) {
        if (needApplyTorques() == false) return (double) 0.0f;

        double target_velocity = m_targetVelocity;
        double maxMotorForce = m_maxMotorForce;

        // current error correction
        if (m_currentLimit != 0) {
            target_velocity = -m_stopERP * m_currentLimitError / (timeStep);
            maxMotorForce = m_maxLimitForce;
        }

        maxMotorForce *= timeStep;

        // current velocity difference

        btVector3 angVelA = new btVector3(body0.getAngularVelocity());
        btVector3 angVelB = new btVector3(body1.getAngularVelocity());

        btVector3 vel_diff;
        vel_diff = angVelA.sub(angVelB);

        double rel_vel = axis.dot(vel_diff);

        // correction velocity
        double motor_relvel = m_limitSoftness * (target_velocity - m_damping * rel_vel);

        if (motor_relvel < btScalar.SIMD_EPSILON && motor_relvel > -btScalar.SIMD_EPSILON) {
            return (double) 0.0f; // no need for applying force
        }

        // correction impulse
        double unclippedMotorImpulse = (1 + m_bounce) * motor_relvel * jacDiagABInv;

        // clip correction impulse
        double clippedMotorImpulse;

        /// @todo: should clip against accumulated impulse
        if (unclippedMotorImpulse > (double) 0.0f) {
            clippedMotorImpulse =
                    unclippedMotorImpulse > maxMotorForce ? maxMotorForce : unclippedMotorImpulse;
        } else {
            clippedMotorImpulse =
                    unclippedMotorImpulse < -maxMotorForce ? -maxMotorForce : unclippedMotorImpulse;
        }

        // sort with accumulated impulses
        double lo = -btScalar.BT_LARGE_FLOAT;
        double hi = btScalar.BT_LARGE_FLOAT;

        double oldaccumImpulse = m_accumulatedImpulse;
        double sum = oldaccumImpulse + clippedMotorImpulse;
        m_accumulatedImpulse = sum > hi ? 0. : sum < lo ? 0. : sum;

        clippedMotorImpulse = m_accumulatedImpulse - oldaccumImpulse;

        btVector3 motorImp = axis.mul(clippedMotorImpulse);

        body0.applyTorqueImpulse(motorImp);
        body1.applyTorqueImpulse(motorImp.negate());

        return clippedMotorImpulse;
    }
}
