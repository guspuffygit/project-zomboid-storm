// Port of BulletDynamics/ConstraintSolver/btTypedConstraint.cpp (Bullet 2.82): class btAngularLimit
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.linearmath.btScalar;

/** btAngularLimit (used by btHingeConstraint with _BT_USE_CENTER_LIMIT_). */
public class btAngularLimit {
    private double m_center;
    private double m_halfRange;
    private double m_softness;
    private double m_biasFactor;
    private double m_relaxationFactor;
    private double m_correction;
    private double m_sign;
    private boolean m_solveLimit;

    /** Default constructor sets up limit as inactive. */
    public btAngularLimit() {
        m_center = (double) 0.0f;
        m_halfRange = (double) -1.0f;
        m_softness = (double) 0.9f;
        m_biasFactor = (double) 0.3f;
        m_relaxationFactor = (double) 1.0f;
        m_correction = (double) 0.0f;
        m_sign = (double) 0.0f;
        m_solveLimit = false;
    }

    /** implicit copy assignment */
    public btAngularLimit set(btAngularLimit o) {
        m_center = o.m_center;
        m_halfRange = o.m_halfRange;
        m_softness = o.m_softness;
        m_biasFactor = o.m_biasFactor;
        m_relaxationFactor = o.m_relaxationFactor;
        m_correction = o.m_correction;
        m_sign = o.m_sign;
        m_solveLimit = o.m_solveLimit;
        return this;
    }

    /** Sets all limit's parameters. (defaults: softness 0.9f, biasFactor 0.3f, relaxation 1.0f) */
    public void set(
            double low,
            double high,
            double _softness,
            double _biasFactor,
            double _relaxationFactor) {
        m_halfRange = (high - low) / 2.0f;
        m_center = btScalar.btNormalizeAngle(low + m_halfRange);
        m_softness = _softness;
        m_biasFactor = _biasFactor;
        m_relaxationFactor = _relaxationFactor;
    }

    public void set(double low, double high, double _softness, double _biasFactor) {
        set(low, high, _softness, _biasFactor, (double) 1.0f);
    }

    public void set(double low, double high, double _softness) {
        set(low, high, _softness, (double) 0.3f, (double) 1.0f);
    }

    public void set(double low, double high) {
        set(low, high, (double) 0.9f, (double) 0.3f, (double) 1.0f);
    }

    /** Checks conastaint angle against limit. If limit is active, correction and sign are set. */
    public void test(final double angle) {
        m_correction = 0.0f;
        m_sign = 0.0f;
        m_solveLimit = false;

        if (m_halfRange >= 0.0f) {
            double deviation = btScalar.btNormalizeAngle(angle - m_center);
            if (deviation < -m_halfRange) {
                m_solveLimit = true;
                m_correction = -(deviation + m_halfRange);
                m_sign = +1.0f;
            } else if (deviation > m_halfRange) {
                m_solveLimit = true;
                m_correction = m_halfRange - deviation;
                m_sign = -1.0f;
            }
        }
    }

    public double getSoftness() {
        return m_softness;
    }

    public double getBiasFactor() {
        return m_biasFactor;
    }

    public double getRelaxationFactor() {
        return m_relaxationFactor;
    }

    public double getCorrection() {
        return m_correction;
    }

    public double getSign() {
        return m_sign;
    }

    public double getHalfRange() {
        return m_halfRange;
    }

    public boolean isLimit() {
        return m_solveLimit;
    }

    /** Checks given angle against limit. If limit is active, the angle is fit into it. */
    public void fit(double[] angle) {
        if (m_halfRange > 0.0f) {
            double relativeAngle = btScalar.btNormalizeAngle(angle[0] - m_center);
            if (!btScalar.btEqual(relativeAngle, m_halfRange)) {
                if (relativeAngle > 0.0f) {
                    angle[0] = getHigh();
                } else {
                    angle[0] = getLow();
                }
            }
        }
    }

    /** Returns correction value multiplied by sign value */
    public double getError() {
        return m_correction * m_sign;
    }

    public double getLow() {
        return btScalar.btNormalizeAngle(m_center - m_halfRange);
    }

    public double getHigh() {
        return btScalar.btNormalizeAngle(m_center + m_halfRange);
    }
}
