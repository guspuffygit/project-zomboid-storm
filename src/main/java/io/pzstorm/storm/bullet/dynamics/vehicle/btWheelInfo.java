// Port of btWheelInfo.h / btWheelInfo.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.vehicle;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btWheelInfo contains information per wheel about friction and suspension.
 *
 * <p>Value type: stored by value in {@code btRaycastVehicle.m_wheelInfo}; {@link #set} is the
 * implicit C++ copy-assignment. Members the C++ constructor leaves uninitialized (m_raycastInfo,
 * m_worldTransform, m_clientInfo, m_clippedInvContactDotSuspension, m_suspensionRelativeVelocity,
 * m_wheelsSuspensionForce, m_skidInfo) start as 0 / identity-less zero here; see
 * docs/re-bullet/dynamics.md.
 */
public class btWheelInfo {

    public static class RaycastInfo {
        // set by raycaster
        public final btVector3 m_contactNormalWS = new btVector3(); // contactnormal
        public final btVector3 m_contactPointWS = new btVector3(); // raycast hitpoint
        public double m_suspensionLength;
        public final btVector3 m_hardPointWS = new btVector3(); // raycast starting point
        public final btVector3 m_wheelDirectionWS = new btVector3(); // direction in worldspace
        public final btVector3 m_wheelAxleWS = new btVector3(); // axle in worldspace
        public boolean m_isInContact;
        public Object m_groundObject; // could be general void* ptr

        /** implicit copy-assignment */
        public RaycastInfo set(RaycastInfo o) {
            m_contactNormalWS.set(o.m_contactNormalWS);
            m_contactPointWS.set(o.m_contactPointWS);
            m_suspensionLength = o.m_suspensionLength;
            m_hardPointWS.set(o.m_hardPointWS);
            m_wheelDirectionWS.set(o.m_wheelDirectionWS);
            m_wheelAxleWS.set(o.m_wheelAxleWS);
            m_isInContact = o.m_isInContact;
            m_groundObject = o.m_groundObject;
            return this;
        }
    }

    public final RaycastInfo m_raycastInfo = new RaycastInfo();

    public final btTransform m_worldTransform = new btTransform();

    public final btVector3 m_chassisConnectionPointCS = new btVector3(); // const
    public final btVector3 m_wheelDirectionCS = new btVector3(); // const
    public final btVector3 m_wheelAxleCS = new btVector3(); // const or modified by steering
    public double m_suspensionRestLength1; // const
    public double m_maxSuspensionTravelCm;
    public double m_wheelsRadius; // const
    public double m_suspensionStiffness; // const
    public double m_wheelsDampingCompression; // const
    public double m_wheelsDampingRelaxation; // const
    public double m_frictionSlip;
    public double m_steering;
    public double m_rotation;
    public double m_deltaRotation;
    public double m_rollInfluence;
    public double m_maxSuspensionForce;

    public double m_engineForce;

    public double m_brake;

    public boolean m_bIsFrontWheel;

    public Object m_clientInfo; // can be used to store pointer to sync transforms...

    public double m_clippedInvContactDotSuspension;
    public double m_suspensionRelativeVelocity;
    // calculated by suspension
    public double m_wheelsSuspensionForce;
    public double m_skidInfo;

    /** Java-only: blank slot for value-mode btAlignedObjectArray storage (no C++ default ctor). */
    public btWheelInfo() {}

    public btWheelInfo(btWheelInfoConstructionInfo ci) {
        m_suspensionRestLength1 = ci.m_suspensionRestLength;
        m_maxSuspensionTravelCm = ci.m_maxSuspensionTravelCm;

        m_wheelsRadius = ci.m_wheelRadius;
        m_suspensionStiffness = ci.m_suspensionStiffness;
        m_wheelsDampingCompression = ci.m_wheelsDampingCompression;
        m_wheelsDampingRelaxation = ci.m_wheelsDampingRelaxation;
        m_chassisConnectionPointCS.set(ci.m_chassisConnectionCS);
        m_wheelDirectionCS.set(ci.m_wheelDirectionCS);
        m_wheelAxleCS.set(ci.m_wheelAxleCS);
        m_frictionSlip = ci.m_frictionSlip;
        m_steering = 0.;
        m_engineForce = 0.;
        m_rotation = 0.;
        m_deltaRotation = 0.;
        m_brake = 0.;
        m_rollInfluence = 0.1;
        m_bIsFrontWheel = ci.m_bIsFrontWheel;
        m_maxSuspensionForce = ci.m_maxSuspensionForce;
    }

    /** implicit copy-assignment ({@code btWheelInfo& operator=(const btWheelInfo&)}) */
    public btWheelInfo set(btWheelInfo o) {
        m_raycastInfo.set(o.m_raycastInfo);
        m_worldTransform.set(o.m_worldTransform);
        m_chassisConnectionPointCS.set(o.m_chassisConnectionPointCS);
        m_wheelDirectionCS.set(o.m_wheelDirectionCS);
        m_wheelAxleCS.set(o.m_wheelAxleCS);
        m_suspensionRestLength1 = o.m_suspensionRestLength1;
        m_maxSuspensionTravelCm = o.m_maxSuspensionTravelCm;
        m_wheelsRadius = o.m_wheelsRadius;
        m_suspensionStiffness = o.m_suspensionStiffness;
        m_wheelsDampingCompression = o.m_wheelsDampingCompression;
        m_wheelsDampingRelaxation = o.m_wheelsDampingRelaxation;
        m_frictionSlip = o.m_frictionSlip;
        m_steering = o.m_steering;
        m_rotation = o.m_rotation;
        m_deltaRotation = o.m_deltaRotation;
        m_rollInfluence = o.m_rollInfluence;
        m_maxSuspensionForce = o.m_maxSuspensionForce;
        m_engineForce = o.m_engineForce;
        m_brake = o.m_brake;
        m_bIsFrontWheel = o.m_bIsFrontWheel;
        m_clientInfo = o.m_clientInfo;
        m_clippedInvContactDotSuspension = o.m_clippedInvContactDotSuspension;
        m_suspensionRelativeVelocity = o.m_suspensionRelativeVelocity;
        m_wheelsSuspensionForce = o.m_wheelsSuspensionForce;
        m_skidInfo = o.m_skidInfo;
        return this;
    }

    public double getSuspensionRestLength() {
        return m_suspensionRestLength1;
    }

    public void updateWheel(btRigidBody chassis, RaycastInfo raycastInfo) {
        if (m_raycastInfo.m_isInContact) {
            double project = m_raycastInfo.m_contactNormalWS.dot(m_raycastInfo.m_wheelDirectionWS);
            btVector3 chassis_velocity_at_contactPoint = new btVector3();
            btVector3 relpos =
                    m_raycastInfo.m_contactPointWS.sub(chassis.getCenterOfMassPosition());
            chassis_velocity_at_contactPoint.set(chassis.getVelocityInLocalPoint(relpos));
            double projVel = m_raycastInfo.m_contactNormalWS.dot(chassis_velocity_at_contactPoint);
            if (project >= -0.1) {
                m_suspensionRelativeVelocity = 0.0;
                m_clippedInvContactDotSuspension = 1.0 / 0.1;
            } else {
                double inv = -1. / project;
                m_suspensionRelativeVelocity = projVel * inv;
                m_clippedInvContactDotSuspension = inv;
            }
        } else { // Not in contact : position wheel in a nice (rest length) position
            m_raycastInfo.m_suspensionLength = this.getSuspensionRestLength();
            m_suspensionRelativeVelocity = 0.0;
            m_raycastInfo.m_contactNormalWS.set(m_raycastInfo.m_wheelDirectionWS.negate());
            m_clippedInvContactDotSuspension = 1.0;
        }
    }
}
