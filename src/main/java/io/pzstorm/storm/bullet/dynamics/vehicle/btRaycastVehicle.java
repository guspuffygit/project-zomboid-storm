// Port of btRaycastVehicle.h / btRaycastVehicle.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.vehicle;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.dynamics.btActionInterface;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btContactConstraint;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btScalarArray;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * rayCast vehicle, very special constraint that turn a rigidbody into a vehicle.
 *
 * <p>Built with {@code #define ROLLING_INFLUENCE_FIX} (as upstream). The C++ non-virtual members
 * are ordinary (overridable) Java methods; the only virtual calls C++ makes internally are
 * updateVehicle (from updateAction) and updateFriction (from updateVehicle, asm {@code call
 * *0x28(%rax)}), and those are the same here.
 */
public class btRaycastVehicle extends btActionInterface {

    public final btAlignedObjectArray<btVector3> m_forwardWS = btAlignedObjectArray.ofVector3();
    public final btAlignedObjectArray<btVector3> m_axle = btAlignedObjectArray.ofVector3();
    public final btScalarArray m_forwardImpulse = new btScalarArray();
    public final btScalarArray m_sideImpulse = new btScalarArray();

    /// backwards compatibility
    public int m_userConstraintType;
    public int m_userConstraintId;

    public static class btVehicleTuning {
        public double m_suspensionStiffness;
        public double m_suspensionCompression;
        public double m_suspensionDamping;
        public double m_maxSuspensionTravelCm;
        public double m_frictionSlip;
        public double m_maxSuspensionForce;

        public btVehicleTuning() {
            m_suspensionStiffness = 5.88;
            m_suspensionCompression = 0.83;
            m_suspensionDamping = 0.88;
            m_maxSuspensionTravelCm = 500.;
            m_frictionSlip = 10.5;
            m_maxSuspensionForce = 6000.;
        }
    }

    public double m_tau;
    public double m_damping;
    public btVehicleRaycaster m_vehicleRaycaster;
    public double m_pitchControl;
    public double m_steeringValue;
    public double m_currentVehicleSpeedKmHour;

    public btRigidBody m_chassisBody;

    public int m_indexRightAxis;
    public int m_indexUpAxis;
    public int m_indexForwardAxis;

    public final btAlignedObjectArray<btWheelInfo> m_wheelInfo =
            new btAlignedObjectArray<>(btWheelInfo::new, btWheelInfo::set);

    /** constructor to create a car from an existing rigidbody */
    public btRaycastVehicle(
            btVehicleTuning tuning, btRigidBody chassis, btVehicleRaycaster raycaster) {
        m_vehicleRaycaster = raycaster;
        m_pitchControl = 0.;
        m_chassisBody = chassis;
        m_indexRightAxis = 0;
        m_indexUpAxis = 2;
        m_indexForwardAxis = 1;
        defaultInit(tuning);
    }

    void defaultInit(btVehicleTuning tuning) {
        m_currentVehicleSpeedKmHour = 0.;
        m_steeringValue = 0.;
    }

    /**
     * {@code virtual ~btRaycastVehicle()}: member arrays in reverse declaration order
     * (asm-confirmed).
     */
    public void destroy() {
        m_wheelInfo.clear();
        m_sideImpulse.clear();
        m_forwardImpulse.clear();
        m_axle.clear();
        m_forwardWS.clear();
    }

    /// btActionInterface interface
    @Override
    public void updateAction(btCollisionWorld collisionWorld, double step) {
        updateVehicle(step);
    }

    //
    // basically most of the code is general for 2 or 4 wheel vehicles, but some of it needs to be
    // reviewed
    //
    public btWheelInfo addWheel(
            btVector3 connectionPointCS,
            btVector3 wheelDirectionCS0,
            btVector3 wheelAxleCS,
            double suspensionRestLength,
            double wheelRadius,
            btVehicleTuning tuning,
            boolean isFrontWheel) {
        btWheelInfoConstructionInfo ci = new btWheelInfoConstructionInfo();

        ci.m_chassisConnectionCS.set(connectionPointCS);
        ci.m_wheelDirectionCS.set(wheelDirectionCS0);
        ci.m_wheelAxleCS.set(wheelAxleCS);
        ci.m_suspensionRestLength = suspensionRestLength;
        ci.m_wheelRadius = wheelRadius;
        ci.m_suspensionStiffness = tuning.m_suspensionStiffness;
        ci.m_wheelsDampingCompression = tuning.m_suspensionCompression;
        ci.m_wheelsDampingRelaxation = tuning.m_suspensionDamping;
        ci.m_frictionSlip = tuning.m_frictionSlip;
        ci.m_bIsFrontWheel = isFrontWheel;
        ci.m_maxSuspensionTravelCm = tuning.m_maxSuspensionTravelCm;
        ci.m_maxSuspensionForce = tuning.m_maxSuspensionForce;

        m_wheelInfo.push_back(new btWheelInfo(ci));

        btWheelInfo wheel = m_wheelInfo.get(getNumWheels() - 1);

        updateWheelTransformsWS(wheel, false);
        updateWheelTransform(getNumWheels() - 1, false);
        return wheel;
    }

    public btTransform getWheelTransformWS(int wheelIndex) {
        btWheelInfo wheel = m_wheelInfo.get(wheelIndex);
        return wheel.m_worldTransform;
    }

    public final void updateWheelTransform(int wheelIndex) {
        updateWheelTransform(wheelIndex, true);
    }

    public void updateWheelTransform(int wheelIndex, boolean interpolatedTransform) {
        btWheelInfo wheel = m_wheelInfo.get(wheelIndex);
        updateWheelTransformsWS(wheel, interpolatedTransform);
        btVector3 up = wheel.m_raycastInfo.m_wheelDirectionWS.negate();
        btVector3 right = wheel.m_raycastInfo.m_wheelAxleWS;
        btVector3 fwd = up.cross(right);
        fwd.set(fwd.normalize());
        //	up = right.cross(fwd);
        //	up.normalize();

        // rotate around steering over de wheelAxleWS
        double steering = wheel.m_steering;

        btQuaternion steeringOrn = new btQuaternion(up, steering); // wheel.m_steering);
        btMatrix3x3 steeringMat = new btMatrix3x3(steeringOrn);

        btQuaternion rotatingOrn = new btQuaternion(right, -wheel.m_rotation);
        btMatrix3x3 rotatingMat = new btMatrix3x3(rotatingOrn);

        btMatrix3x3 basis2 =
                new btMatrix3x3(
                        right.get(0),
                        fwd.get(0),
                        up.get(0),
                        right.get(1),
                        fwd.get(1),
                        up.get(1),
                        right.get(2),
                        fwd.get(2),
                        up.get(2));

        wheel.m_worldTransform.setBasis(steeringMat.mul(rotatingMat).mul(basis2));
        wheel.m_worldTransform.setOrigin(
                wheel.m_raycastInfo.m_hardPointWS.add(
                        wheel.m_raycastInfo.m_wheelDirectionWS.mul(
                                wheel.m_raycastInfo.m_suspensionLength)));
    }

    public void resetSuspension() {
        int i;
        for (i = 0; i < m_wheelInfo.size(); i++) {
            btWheelInfo wheel = m_wheelInfo.get(i);
            wheel.m_raycastInfo.m_suspensionLength = wheel.getSuspensionRestLength();
            wheel.m_suspensionRelativeVelocity = 0.0;

            wheel.m_raycastInfo.m_contactNormalWS.set(
                    wheel.m_raycastInfo.m_wheelDirectionWS.negate());
            // wheel_info.setContactFriction(btScalar(0.0));
            wheel.m_clippedInvContactDotSuspension = 1.0;
        }
    }

    public final void updateWheelTransformsWS(btWheelInfo wheel) {
        updateWheelTransformsWS(wheel, true);
    }

    public void updateWheelTransformsWS(btWheelInfo wheel, boolean interpolatedTransform) {
        wheel.m_raycastInfo.m_isInContact = false;

        btTransform chassisTrans = new btTransform(getChassisWorldTransform());
        if (interpolatedTransform && (getRigidBody().getMotionState() != null)) {
            getRigidBody().getMotionState().getWorldTransform(chassisTrans);
        }

        wheel.m_raycastInfo.m_hardPointWS.set(chassisTrans.mul(wheel.m_chassisConnectionPointCS));
        wheel.m_raycastInfo.m_wheelDirectionWS.set(
                chassisTrans.getBasis().mul(wheel.m_wheelDirectionCS));
        wheel.m_raycastInfo.m_wheelAxleWS.set(chassisTrans.getBasis().mul(wheel.m_wheelAxleCS));
    }

    public double rayCast(btWheelInfo wheel) {
        updateWheelTransformsWS(wheel, false);

        double depth = -1;

        double raylen = wheel.getSuspensionRestLength() + wheel.m_wheelsRadius;

        btVector3 rayvector = wheel.m_raycastInfo.m_wheelDirectionWS.mul(raylen);
        btVector3 source = wheel.m_raycastInfo.m_hardPointWS;
        wheel.m_raycastInfo.m_contactPointWS.set(source.add(rayvector));
        btVector3 target = wheel.m_raycastInfo.m_contactPointWS;

        double param = 0.;

        btVehicleRaycaster.btVehicleRaycasterResult rayResults =
                new btVehicleRaycaster.btVehicleRaycasterResult();

        Object object = m_vehicleRaycaster.castRay(source, target, rayResults);

        wheel.m_raycastInfo.m_groundObject = null;

        if (object != null) {
            param = rayResults.m_distFraction;
            depth = raylen * rayResults.m_distFraction;
            wheel.m_raycastInfo.m_contactNormalWS.set(rayResults.m_hitNormalInWorld);
            wheel.m_raycastInfo.m_isInContact = true;

            wheel.m_raycastInfo.m_groundObject =
                    getFixedBody(); /// @todo for driving on dynamic/movable objects!;
            // wheel.m_raycastInfo.m_groundObject = object;

            double hitDistance = param * raylen;
            wheel.m_raycastInfo.m_suspensionLength = hitDistance - wheel.m_wheelsRadius;
            // clamp on max suspension travel

            double minSuspensionLength =
                    wheel.getSuspensionRestLength() - wheel.m_maxSuspensionTravelCm * 0.01;
            double maxSuspensionLength =
                    wheel.getSuspensionRestLength() + wheel.m_maxSuspensionTravelCm * 0.01;
            if (wheel.m_raycastInfo.m_suspensionLength < minSuspensionLength) {
                wheel.m_raycastInfo.m_suspensionLength = minSuspensionLength;
            }
            if (wheel.m_raycastInfo.m_suspensionLength > maxSuspensionLength) {
                wheel.m_raycastInfo.m_suspensionLength = maxSuspensionLength;
            }

            wheel.m_raycastInfo.m_contactPointWS.set(rayResults.m_hitPointInWorld);

            double denominator =
                    wheel.m_raycastInfo.m_contactNormalWS.dot(
                            wheel.m_raycastInfo.m_wheelDirectionWS);

            btVector3 chassis_velocity_at_contactPoint = new btVector3();
            btVector3 relpos =
                    wheel.m_raycastInfo.m_contactPointWS.sub(
                            getRigidBody().getCenterOfMassPosition());

            chassis_velocity_at_contactPoint.set(getRigidBody().getVelocityInLocalPoint(relpos));

            double projVel =
                    wheel.m_raycastInfo.m_contactNormalWS.dot(chassis_velocity_at_contactPoint);

            if (denominator >= -0.1) {
                wheel.m_suspensionRelativeVelocity = 0.0;
                wheel.m_clippedInvContactDotSuspension = 1.0 / 0.1;
            } else {
                double inv = -1. / denominator;
                wheel.m_suspensionRelativeVelocity = projVel * inv;
                wheel.m_clippedInvContactDotSuspension = inv;
            }

        } else {
            // put wheel info as in rest position
            wheel.m_raycastInfo.m_suspensionLength = wheel.getSuspensionRestLength();
            wheel.m_suspensionRelativeVelocity = 0.0;
            wheel.m_raycastInfo.m_contactNormalWS.set(
                    wheel.m_raycastInfo.m_wheelDirectionWS.negate());
            wheel.m_clippedInvContactDotSuspension = 1.0;
        }

        return depth;
    }

    public btTransform getChassisWorldTransform() {
        return getRigidBody().getCenterOfMassTransform();
    }

    public void updateVehicle(double step) {
        {
            for (int i = 0; i < getNumWheels(); i++) {
                updateWheelTransform(i, false);
            }
        }

        m_currentVehicleSpeedKmHour = 3.6 * getRigidBody().getLinearVelocity().length();

        btTransform chassisTrans = getChassisWorldTransform();

        btVector3 forwardW =
                new btVector3(
                        chassisTrans.getBasis().get(0, m_indexForwardAxis),
                        chassisTrans.getBasis().get(1, m_indexForwardAxis),
                        chassisTrans.getBasis().get(2, m_indexForwardAxis));

        if (forwardW.dot(getRigidBody().getLinearVelocity()) < 0.) {
            m_currentVehicleSpeedKmHour *= -1.;
        }

        //
        // simulate suspension
        //

        int i = 0;
        for (i = 0; i < m_wheelInfo.size(); i++) {
            double depth;
            depth = rayCast(m_wheelInfo.get(i));
        }

        updateSuspension(step);

        for (i = 0; i < m_wheelInfo.size(); i++) {
            // apply suspension force
            btWheelInfo wheel = m_wheelInfo.get(i);

            double suspensionForce = wheel.m_wheelsSuspensionForce;

            if (suspensionForce > wheel.m_maxSuspensionForce) {
                suspensionForce = wheel.m_maxSuspensionForce;
            }
            btVector3 impulse =
                    wheel.m_raycastInfo.m_contactNormalWS.mul(suspensionForce).mul(step);
            btVector3 relpos =
                    wheel.m_raycastInfo.m_contactPointWS.sub(
                            getRigidBody().getCenterOfMassPosition());

            getRigidBody().applyImpulse(impulse, relpos);
        }

        updateFriction(step);

        for (i = 0; i < m_wheelInfo.size(); i++) {
            btWheelInfo wheel = m_wheelInfo.get(i);
            btVector3 relpos =
                    wheel.m_raycastInfo.m_hardPointWS.sub(getRigidBody().getCenterOfMassPosition());
            btVector3 vel = getRigidBody().getVelocityInLocalPoint(relpos);

            if (wheel.m_raycastInfo.m_isInContact) {
                btTransform chassisWorldTransform = getChassisWorldTransform();

                btVector3 fwd =
                        new btVector3(
                                chassisWorldTransform.getBasis().get(0, m_indexForwardAxis),
                                chassisWorldTransform.getBasis().get(1, m_indexForwardAxis),
                                chassisWorldTransform.getBasis().get(2, m_indexForwardAxis));

                double proj = fwd.dot(wheel.m_raycastInfo.m_contactNormalWS);
                fwd.subLocal(wheel.m_raycastInfo.m_contactNormalWS.mul(proj));

                double proj2 = fwd.dot(vel);

                wheel.m_deltaRotation = (proj2 * step) / (wheel.m_wheelsRadius);
                wheel.m_rotation += wheel.m_deltaRotation;

            } else {
                wheel.m_rotation += wheel.m_deltaRotation;
            }

            wheel.m_deltaRotation *= 0.99; // damping of rotation when not in contact
        }
    }

    public void setSteeringValue(double steering, int wheel) {
        btWheelInfo wheelInfo = getWheelInfo(wheel);
        wheelInfo.m_steering = steering;
    }

    public double getSteeringValue(int wheel) {
        return getWheelInfo(wheel).m_steering;
    }

    public void applyEngineForce(double force, int wheel) {
        btWheelInfo wheelInfo = getWheelInfo(wheel);
        wheelInfo.m_engineForce = force;
    }

    public int getNumWheels() {
        return m_wheelInfo.size();
    }

    public btWheelInfo getWheelInfo(int index) {
        return m_wheelInfo.get(index);
    }

    public void setBrake(double brake, int wheelIndex) {
        getWheelInfo(wheelIndex).m_brake = brake;
    }

    public void setPitchControl(double pitch) {
        m_pitchControl = pitch;
    }

    public void updateSuspension(double deltaTime) {
        double chassisMass = 1. / m_chassisBody.getInvMass();

        for (int w_it = 0; w_it < getNumWheels(); w_it++) {
            btWheelInfo wheel_info = m_wheelInfo.get(w_it);

            if (wheel_info.m_raycastInfo.m_isInContact) {
                double force;
                //	Spring
                {
                    double susp_length = wheel_info.getSuspensionRestLength();
                    double current_length = wheel_info.m_raycastInfo.m_suspensionLength;

                    double length_diff = (susp_length - current_length);

                    force =
                            wheel_info.m_suspensionStiffness
                                    * length_diff
                                    * wheel_info.m_clippedInvContactDotSuspension;
                }

                // Damper
                {
                    double projected_rel_vel = wheel_info.m_suspensionRelativeVelocity;
                    {
                        double susp_damping;
                        if (projected_rel_vel < 0.0) {
                            susp_damping = wheel_info.m_wheelsDampingCompression;
                        } else {
                            susp_damping = wheel_info.m_wheelsDampingRelaxation;
                        }
                        force -= susp_damping * projected_rel_vel;
                    }
                }

                // RESULT
                wheel_info.m_wheelsSuspensionForce = force * chassisMass;
                if (wheel_info.m_wheelsSuspensionForce < 0.) {
                    wheel_info.m_wheelsSuspensionForce = 0.;
                }
            } else {
                wheel_info.m_wheelsSuspensionForce = 0.0;
            }
        }
    }

    /** file-scope {@code struct btWheelContactPoint} in btRaycastVehicle.cpp */
    public static class btWheelContactPoint {
        public btRigidBody m_body0;
        public btRigidBody m_body1;
        public final btVector3 m_frictionPositionWorld = new btVector3();
        public final btVector3 m_frictionDirectionWorld = new btVector3();
        public double m_jacDiagABInv;
        public double m_maxImpulse;

        public btWheelContactPoint(
                btRigidBody body0,
                btRigidBody body1,
                btVector3 frictionPosWorld,
                btVector3 frictionDirectionWorld,
                double maxImpulse) {
            m_body0 = body0;
            m_body1 = body1;
            m_frictionPositionWorld.set(frictionPosWorld);
            m_frictionDirectionWorld.set(frictionDirectionWorld);
            m_maxImpulse = maxImpulse;
            double denom0 =
                    body0.computeImpulseDenominator(frictionPosWorld, frictionDirectionWorld);
            double denom1 =
                    body1.computeImpulseDenominator(frictionPosWorld, frictionDirectionWorld);
            double relaxation = (double) 1.f;
            m_jacDiagABInv = relaxation / (denom0 + denom1);
        }
    }

    /** file-scope {@code btScalar calcRollingFriction(btWheelContactPoint& contactPoint)} */
    public static double calcRollingFriction(btWheelContactPoint contactPoint) {
        double j1 = (double) 0.f;

        btVector3 contactPosWorld = contactPoint.m_frictionPositionWorld;

        btVector3 rel_pos1 = contactPosWorld.sub(contactPoint.m_body0.getCenterOfMassPosition());
        btVector3 rel_pos2 = contactPosWorld.sub(contactPoint.m_body1.getCenterOfMassPosition());

        double maxImpulse = contactPoint.m_maxImpulse;

        btVector3 vel1 = contactPoint.m_body0.getVelocityInLocalPoint(rel_pos1);
        btVector3 vel2 = contactPoint.m_body1.getVelocityInLocalPoint(rel_pos2);
        btVector3 vel = vel1.sub(vel2);

        double vrel = contactPoint.m_frictionDirectionWorld.dot(vel);

        // calculate j that moves us to zero relative velocity
        j1 = -vrel * contactPoint.m_jacDiagABInv;
        // btSetMin(j1, maxImpulse);
        if (maxImpulse < j1) {
            j1 = maxImpulse;
        }
        // btSetMax(j1, -maxImpulse);
        if (j1 < -maxImpulse) {
            j1 = -maxImpulse;
        }

        return j1;
    }

    /** file-scope global {@code btScalar sideFrictionStiffness2 = btScalar(1.0);} */
    public static double sideFrictionStiffness2 = 1.0;

    public void updateFriction(double timeStep) {
        // calculate the impulse, so that the wheels don't move sidewards
        int numWheel = getNumWheels();
        if (numWheel == 0) {
            return;
        }

        m_forwardWS.resize(numWheel);
        m_axle.resize(numWheel);
        m_forwardImpulse.resize(numWheel);
        m_sideImpulse.resize(numWheel);

        int numWheelsOnGround = 0;

        // collapse all those loops into one!
        for (int i = 0; i < getNumWheels(); i++) {
            btWheelInfo wheelInfo = m_wheelInfo.get(i);
            btRigidBody groundObject = (btRigidBody) wheelInfo.m_raycastInfo.m_groundObject;
            if (groundObject != null) {
                numWheelsOnGround++;
            }
            m_sideImpulse.set(i, 0.);
            m_forwardImpulse.set(i, 0.);
        }

        {
            for (int i = 0; i < getNumWheels(); i++) {
                btWheelInfo wheelInfo = m_wheelInfo.get(i);

                btRigidBody groundObject = (btRigidBody) wheelInfo.m_raycastInfo.m_groundObject;

                if (groundObject != null) {
                    btTransform wheelTrans = getWheelTransformWS(i);

                    btMatrix3x3 wheelBasis0 = new btMatrix3x3(wheelTrans.getBasis());
                    m_axle.set(
                            i,
                            new btVector3(
                                    wheelBasis0.get(0, m_indexRightAxis),
                                    wheelBasis0.get(1, m_indexRightAxis),
                                    wheelBasis0.get(2, m_indexRightAxis)));

                    btVector3 surfNormalWS = wheelInfo.m_raycastInfo.m_contactNormalWS;
                    double proj = m_axle.get(i).dot(surfNormalWS);
                    m_axle.get(i).subLocal(surfNormalWS.mul(proj));
                    m_axle.get(i).set(m_axle.get(i).normalize());

                    m_forwardWS.set(i, surfNormalWS.cross(m_axle.get(i)));
                    m_forwardWS.get(i).normalize();

                    double[] sideImpulse = {m_sideImpulse.get(i)};
                    btContactConstraint.resolveSingleBilateral(
                            m_chassisBody,
                            wheelInfo.m_raycastInfo.m_contactPointWS,
                            groundObject,
                            wheelInfo.m_raycastInfo.m_contactPointWS,
                            0.,
                            m_axle.get(i),
                            sideImpulse,
                            timeStep);
                    m_sideImpulse.set(i, sideImpulse[0]);

                    m_sideImpulse.set(i, m_sideImpulse.get(i) * sideFrictionStiffness2);
                }
            }
        }

        double sideFactor = 1.;
        double fwdFactor = 0.5;

        boolean sliding = false;
        {
            for (int wheel = 0; wheel < getNumWheels(); wheel++) {
                btWheelInfo wheelInfo = m_wheelInfo.get(wheel);
                btRigidBody groundObject = (btRigidBody) wheelInfo.m_raycastInfo.m_groundObject;

                double rollingFriction = (double) 0.f;

                if (groundObject != null) {
                    if (wheelInfo.m_engineForce != (double) 0.f) {
                        rollingFriction = wheelInfo.m_engineForce * timeStep;
                    } else {
                        double defaultRollingFrictionImpulse = (double) 0.f;
                        double maxImpulse =
                                wheelInfo.m_brake != 0
                                        ? wheelInfo.m_brake
                                        : defaultRollingFrictionImpulse;
                        btWheelContactPoint contactPt =
                                new btWheelContactPoint(
                                        m_chassisBody,
                                        groundObject,
                                        wheelInfo.m_raycastInfo.m_contactPointWS,
                                        m_forwardWS.get(wheel),
                                        maxImpulse);
                        rollingFriction = calcRollingFriction(contactPt);
                    }
                }

                // switch between active rolling (throttle), braking and non-active rolling friction
                // (no throttle/break)

                m_forwardImpulse.set(wheel, 0.);
                m_wheelInfo.get(wheel).m_skidInfo = 1.;

                if (groundObject != null) {
                    m_wheelInfo.get(wheel).m_skidInfo = 1.;

                    double maximp =
                            wheelInfo.m_wheelsSuspensionForce * timeStep * wheelInfo.m_frictionSlip;
                    double maximpSide = maximp;

                    double maximpSquared = maximp * maximpSide;

                    m_forwardImpulse.set(
                            wheel, rollingFriction); // wheelInfo.m_engineForce* timeStep;

                    double x = (m_forwardImpulse.get(wheel)) * fwdFactor;
                    double y = (m_sideImpulse.get(wheel)) * sideFactor;

                    double impulseSquared = (x * x + y * y);

                    if (impulseSquared > maximpSquared) {
                        sliding = true;

                        double factor = maximp / btScalar.btSqrt(impulseSquared);

                        m_wheelInfo.get(wheel).m_skidInfo *= factor;
                    }
                }
            }
        }

        if (sliding) {
            for (int wheel = 0; wheel < getNumWheels(); wheel++) {
                if (m_sideImpulse.get(wheel) != 0.) {
                    if (m_wheelInfo.get(wheel).m_skidInfo < 1.) {
                        m_forwardImpulse.set(
                                wheel,
                                m_forwardImpulse.get(wheel) * m_wheelInfo.get(wheel).m_skidInfo);
                        m_sideImpulse.set(
                                wheel,
                                m_sideImpulse.get(wheel) * m_wheelInfo.get(wheel).m_skidInfo);
                    }
                }
            }
        }

        // apply the impulses
        {
            for (int wheel = 0; wheel < getNumWheels(); wheel++) {
                btWheelInfo wheelInfo = m_wheelInfo.get(wheel);

                btVector3 rel_pos =
                        wheelInfo.m_raycastInfo.m_contactPointWS.sub(
                                m_chassisBody.getCenterOfMassPosition());

                if (m_forwardImpulse.get(wheel) != 0.) {
                    m_chassisBody.applyImpulse(
                            m_forwardWS.get(wheel).mul(m_forwardImpulse.get(wheel)), rel_pos);
                }
                if (m_sideImpulse.get(wheel) != 0.) {
                    btRigidBody groundObject =
                            (btRigidBody) m_wheelInfo.get(wheel).m_raycastInfo.m_groundObject;

                    btVector3 rel_pos2 =
                            wheelInfo.m_raycastInfo.m_contactPointWS.sub(
                                    groundObject.getCenterOfMassPosition());

                    btVector3 sideImp = m_axle.get(wheel).mul(m_sideImpulse.get(wheel));

                    // ROLLING_INFLUENCE_FIX: fix. It only worked if car's up was along Y - VT.
                    btVector3 vChassisWorldUp =
                            getRigidBody()
                                    .getCenterOfMassTransform()
                                    .getBasis()
                                    .getColumn(m_indexUpAxis);
                    rel_pos.subLocal(
                            vChassisWorldUp.mul(
                                    vChassisWorldUp.dot(rel_pos)
                                            * ((double) 1.f - wheelInfo.m_rollInfluence)));
                    m_chassisBody.applyImpulse(sideImp, rel_pos);

                    // apply friction impulse on the ground
                    groundObject.applyImpulse(sideImp.negate(), rel_pos2);
                }
            }
        }
    }

    public btRigidBody getRigidBody() {
        return m_chassisBody;
    }

    public int getRightAxis() {
        return m_indexRightAxis;
    }

    public int getUpAxis() {
        return m_indexUpAxis;
    }

    public int getForwardAxis() {
        return m_indexForwardAxis;
    }

    /// Worldspace forward vector
    public btVector3 getForwardVector() {
        btTransform chassisTrans = getChassisWorldTransform();

        btVector3 forwardW =
                new btVector3(
                        chassisTrans.getBasis().get(0, m_indexForwardAxis),
                        chassisTrans.getBasis().get(1, m_indexForwardAxis),
                        chassisTrans.getBasis().get(2, m_indexForwardAxis));

        return forwardW;
    }

    /// Velocity of vehicle (positive if velocity vector has same direction as foward vector)
    public double getCurrentSpeedKmHour() {
        return m_currentVehicleSpeedKmHour;
    }

    public void setCoordinateSystem(int rightIndex, int upIndex, int forwardIndex) {
        m_indexRightAxis = rightIndex;
        m_indexUpAxis = upIndex;
        m_indexForwardAxis = forwardIndex;
    }

    /// backwards compatibility
    public int getUserConstraintType() {
        return m_userConstraintType;
    }

    public void setUserConstraintType(int userConstraintType) {
        m_userConstraintType = userConstraintType;
    }

    public void setUserConstraintId(int uid) {
        m_userConstraintId = uid;
    }

    public int getUserConstraintId() {
        return m_userConstraintId;
    }

    /// btActionInterface interface
    @Override
    public void debugDraw(btIDebugDraw debugDrawer) {
        for (int v = 0; v < this.getNumWheels(); v++) {
            btVector3 wheelColor = new btVector3(0, 1, 1);
            if (getWheelInfo(v).m_raycastInfo.m_isInContact) {
                wheelColor.setValue(0, 0, 1);
            } else {
                wheelColor.setValue(1, 0, 1);
            }

            btVector3 wheelPosWS = new btVector3(getWheelInfo(v).m_worldTransform.getOrigin());

            btVector3 axle =
                    new btVector3(
                            getWheelInfo(v).m_worldTransform.getBasis().get(0, getRightAxis()),
                            getWheelInfo(v).m_worldTransform.getBasis().get(1, getRightAxis()),
                            getWheelInfo(v).m_worldTransform.getBasis().get(2, getRightAxis()));

            // debug wheels (cylinders)
            debugDrawer.drawLine(wheelPosWS, wheelPosWS.add(axle), wheelColor);
            debugDrawer.drawLine(
                    wheelPosWS, getWheelInfo(v).m_raycastInfo.m_contactPointWS, wheelColor);
        }
    }
}
