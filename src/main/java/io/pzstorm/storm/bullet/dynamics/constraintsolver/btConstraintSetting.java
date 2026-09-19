// Port of BulletDynamics/ConstraintSolver/btPoint2PointConstraint.h (Bullet 2.82): struct
// btConstraintSetting
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

public class btConstraintSetting {
    public double m_tau;
    public double m_damping;
    public double m_impulseClamp;

    public btConstraintSetting() {
        m_tau = 0.3;
        m_damping = 1.;
        m_impulseClamp = 0.;
    }
}
