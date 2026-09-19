// Port of btWheelInfo.h (Bullet 2.82) — struct btWheelInfoConstructionInfo
package io.pzstorm.storm.bullet.dynamics.vehicle;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * POD struct; C++ leaves every member uninitialized (addWheel assigns all of them), Java zeroes
 * them.
 */
public class btWheelInfoConstructionInfo {
    public final btVector3 m_chassisConnectionCS = new btVector3();
    public final btVector3 m_wheelDirectionCS = new btVector3();
    public final btVector3 m_wheelAxleCS = new btVector3();
    public double m_suspensionRestLength;
    public double m_maxSuspensionTravelCm;
    public double m_wheelRadius;

    public double m_suspensionStiffness;
    public double m_wheelsDampingCompression;
    public double m_wheelsDampingRelaxation;
    public double m_frictionSlip;
    public double m_maxSuspensionForce;
    public boolean m_bIsFrontWheel;
}
