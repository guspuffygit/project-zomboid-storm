// Port of btRigidBody.cpp (Bullet 2.82) + inline members of btRigidBody.h.
// C++ sizeof(btRigidBody) == 0x4a0 (btAlignedAllocInternal(0x4a0,0x10) at every `new btRigidBody`
// site in the .so).
package io.pzstorm.storm.bullet.dynamics;

import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseProxy;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btTypedConstraint;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btMinMax;
import io.pzstorm.storm.bullet.linearmath.btMotionState;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btTransformUtil;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The btRigidBody is the main class for rigid body objects. It is derived from btCollisionObject,
 * so it keeps a pointer to a btCollisionShape.
 */
public class btRigidBody extends btCollisionObject {

    /**
     * {@code sizeof(btRigidBody)} in the double-precision .so (for btAlignedAlloc / PTR-ORDER at
     * creation sites).
     */
    public static final int SIZEOF = 0x4a0;

    // enum btRigidBodyFlags
    public static final int BT_DISABLE_WORLD_GRAVITY = 1;
    public static final int BT_ENABLE_GYROPSCOPIC_FORCE = 2;

    public final btMatrix3x3 m_invInertiaTensorWorld = new btMatrix3x3();
    public final btVector3 m_linearVelocity = new btVector3();
    public final btVector3 m_angularVelocity = new btVector3();
    public double m_inverseMass;
    public final btVector3 m_linearFactor = new btVector3();

    public final btVector3 m_gravity = new btVector3();
    public final btVector3 m_gravity_acceleration = new btVector3();
    public final btVector3 m_invInertiaLocal = new btVector3();
    public final btVector3 m_totalForce = new btVector3();
    public final btVector3 m_totalTorque = new btVector3();

    public double m_linearDamping;
    public double m_angularDamping;

    public boolean m_additionalDamping;
    public double m_additionalDampingFactor;
    public double m_additionalLinearDampingThresholdSqr;
    public double m_additionalAngularDampingThresholdSqr;
    public double m_additionalAngularDampingFactor;

    public double m_linearSleepingThreshold;
    public double m_angularSleepingThreshold;

    /**
     * m_optionalMotionState allows to automatic synchronize the world transform for active objects
     */
    public btMotionState m_optionalMotionState;

    /** keep track of typed constraints referencing this rigid body */
    public final btAlignedObjectArray<btTypedConstraint> m_constraintRefs =
            new btAlignedObjectArray<>();

    public int m_rigidbodyFlags;

    public int m_debugBodyId;

    // protected:
    public final btVector3 m_deltaLinearVelocity = new btVector3();
    public final btVector3 m_deltaAngularVelocity = new btVector3();
    public final btVector3 m_angularFactor = new btVector3();
    public final btVector3 m_invMass = new btVector3();
    public final btVector3 m_pushVelocity = new btVector3();
    public final btVector3 m_turnVelocity = new btVector3();

    public int m_contactSolverType;
    public int m_frictionSolverType;

    /** The btRigidBodyConstructionInfo structure provides information to create a rigid body. */
    public static class btRigidBodyConstructionInfo {
        public double m_mass;

        /**
         * When a motionState is provided, the rigid body will initialize its world transform from
         * the motion state.
         */
        public btMotionState m_motionState;

        public final btTransform m_startWorldTransform = new btTransform();

        public btCollisionShape m_collisionShape;
        public final btVector3 m_localInertia = new btVector3();
        public double m_linearDamping;
        public double m_angularDamping;

        public double m_friction;
        public double m_rollingFriction;
        public double m_restitution;

        public double m_linearSleepingThreshold;
        public double m_angularSleepingThreshold;

        public boolean m_additionalDamping;
        public double m_additionalDampingFactor;
        public double m_additionalLinearDampingThresholdSqr;
        public double m_additionalAngularDampingThresholdSqr;
        public double m_additionalAngularDampingFactor;

        public btRigidBodyConstructionInfo(
                double mass,
                btMotionState motionState,
                btCollisionShape collisionShape,
                btVector3 localInertia) {
            m_mass = mass;
            m_motionState = motionState;
            m_collisionShape = collisionShape;
            m_localInertia.set(localInertia);
            m_linearDamping = 0.;
            m_angularDamping = 0.;
            m_friction = 0.5;
            m_rollingFriction = 0;
            m_restitution = 0.;
            m_linearSleepingThreshold = 0.8;
            m_angularSleepingThreshold = (double) 1.f;
            m_additionalDamping = false;
            m_additionalDampingFactor = 0.005;
            m_additionalLinearDampingThresholdSqr = 0.01;
            m_additionalAngularDampingThresholdSqr = 0.01;
            m_additionalAngularDampingFactor = 0.01;
            m_startWorldTransform.setIdentity();
        }

        /** Default argument: localInertia=btVector3(0,0,0). */
        public btRigidBodyConstructionInfo(
                double mass, btMotionState motionState, btCollisionShape collisionShape) {
            this(mass, motionState, collisionShape, new btVector3(0, 0, 0));
        }
    }

    public btRigidBody(btRigidBodyConstructionInfo constructionInfo) {
        setupRigidBody(constructionInfo);
    }

    public btRigidBody(
            double mass,
            btMotionState motionState,
            btCollisionShape collisionShape,
            btVector3 localInertia) {
        btRigidBodyConstructionInfo cinfo =
                new btRigidBodyConstructionInfo(mass, motionState, collisionShape, localInertia);
        setupRigidBody(cinfo);
    }

    /** Default argument: localInertia=btVector3(0,0,0). */
    public btRigidBody(double mass, btMotionState motionState, btCollisionShape collisionShape) {
        this(mass, motionState, collisionShape, new btVector3(0, 0, 0));
    }

    /** setupRigidBody is only used internally by the constructor */
    protected void setupRigidBody(btRigidBodyConstructionInfo constructionInfo) {
        m_internalType = CO_RIGID_BODY;

        m_linearVelocity.setValue(0.0, 0.0, 0.0);
        m_angularVelocity.setValue(0., 0., 0.);
        m_angularFactor.setValue(1, 1, 1);
        m_linearFactor.setValue(1, 1, 1);
        m_gravity.setValue(0.0, 0.0, 0.0);
        m_gravity_acceleration.setValue(0.0, 0.0, 0.0);
        m_totalForce.setValue(0.0, 0.0, 0.0);
        m_totalTorque.setValue(0.0, 0.0, 0.0);
        setDamping(constructionInfo.m_linearDamping, constructionInfo.m_angularDamping);

        m_linearSleepingThreshold = constructionInfo.m_linearSleepingThreshold;
        m_angularSleepingThreshold = constructionInfo.m_angularSleepingThreshold;
        m_optionalMotionState = constructionInfo.m_motionState;
        m_contactSolverType = 0;
        m_frictionSolverType = 0;
        m_additionalDamping = constructionInfo.m_additionalDamping;
        m_additionalDampingFactor = constructionInfo.m_additionalDampingFactor;
        m_additionalLinearDampingThresholdSqr =
                constructionInfo.m_additionalLinearDampingThresholdSqr;
        m_additionalAngularDampingThresholdSqr =
                constructionInfo.m_additionalAngularDampingThresholdSqr;
        m_additionalAngularDampingFactor = constructionInfo.m_additionalAngularDampingFactor;

        if (m_optionalMotionState != null) {
            m_optionalMotionState.getWorldTransform(m_worldTransform);
        } else {
            m_worldTransform.set(constructionInfo.m_startWorldTransform);
        }

        m_interpolationWorldTransform.set(m_worldTransform);
        m_interpolationLinearVelocity.setValue(0, 0, 0);
        m_interpolationAngularVelocity.setValue(0, 0, 0);

        // moved to btCollisionObject
        m_friction = constructionInfo.m_friction;
        m_rollingFriction = constructionInfo.m_rollingFriction;
        m_restitution = constructionInfo.m_restitution;

        setCollisionShape(constructionInfo.m_collisionShape);
        m_debugBodyId = btGlobals.uniqueId++;

        setMassProps(constructionInfo.m_mass, constructionInfo.m_localInertia);
        updateInertiaTensor();

        m_rigidbodyFlags = 0;

        m_deltaLinearVelocity.setZero();
        m_deltaAngularVelocity.setZero();
        m_invMass.set(m_linearFactor.mul(m_inverseMass));
        m_pushVelocity.setZero();
        m_turnVelocity.setZero();
    }

    public void proceedToTransform(btTransform newTrans) {
        setCenterOfMassTransform(newTrans);
    }

    /**
     * to keep collision detection and dynamics separate we don't store a rigidbody pointer but a
     * rigidbody is derived from btCollisionObject, so we can safely perform an upcast
     */
    public static btRigidBody upcast(btCollisionObject colObj) {
        if ((colObj.getInternalType() & btCollisionObject.CO_RIGID_BODY) != 0) {
            return (btRigidBody) colObj;
        }
        return null;
    }

    /** continuous collision detection needs prediction */
    public void predictIntegratedTransform(double timeStep, btTransform predictedTransform) {
        btTransformUtil.integrateTransform(
                m_worldTransform,
                m_linearVelocity,
                m_angularVelocity,
                timeStep,
                predictedTransform);
    }

    public void saveKinematicState(double timeStep) {
        // todo: clamp to some (user definable) safe minimum timestep, to limit maximum angular
        // motion
        if (timeStep != 0.) {
            // if we use motionstate to synchronize world transforms, get the new kinematic/animated
            // world transform
            if (getMotionState() != null) {
                getMotionState().getWorldTransform(m_worldTransform);
            }
            btTransformUtil.calculateVelocity(
                    m_interpolationWorldTransform,
                    m_worldTransform,
                    timeStep,
                    m_linearVelocity,
                    m_angularVelocity);
            m_interpolationLinearVelocity.set(m_linearVelocity);
            m_interpolationAngularVelocity.set(m_angularVelocity);
            m_interpolationWorldTransform.set(m_worldTransform);
        }
    }

    public void applyGravity() {
        if (isStaticOrKinematicObject()) {
            return;
        }
        applyCentralForce(m_gravity);
    }

    public void setGravity(btVector3 acceleration) {
        if (m_inverseMass != 0.0) {
            m_gravity.set(acceleration.mul(1.0 / m_inverseMass));
        }
        m_gravity_acceleration.set(acceleration);
    }

    public btVector3 getGravity() {
        return m_gravity_acceleration;
    }

    public void setDamping(double lin_damping, double ang_damping) {
        m_linearDamping = btMinMax.btClamped(lin_damping, 0.0, 1.0);
        m_angularDamping = btMinMax.btClamped(ang_damping, 0.0, 1.0);
    }

    public double getLinearDamping() {
        return m_linearDamping;
    }

    public double getAngularDamping() {
        return m_angularDamping;
    }

    public double getLinearSleepingThreshold() {
        return m_linearSleepingThreshold;
    }

    public double getAngularSleepingThreshold() {
        return m_angularSleepingThreshold;
    }

    /** applyDamping damps the velocity, using the given m_linearDamping and m_angularDamping */
    public void applyDamping(double timeStep) {
        // On new damping: see discussion/issue report here:
        // http://code.google.com/p/bullet/issues/detail?id=74
        // todo: do some performance comparisons (but other parts of the engine are probably
        // bottleneck anyway

        m_linearVelocity.mulLocal(btScalar.btPow(1 - m_linearDamping, timeStep));
        m_angularVelocity.mulLocal(btScalar.btPow(1 - m_angularDamping, timeStep));

        if (m_additionalDamping) {
            // Additional damping can help avoiding lowpass jitter motion, help stability for
            // ragdolls
            // etc. Such damping is undesirable, so once the overall simulation quality of the rigid
            // body dynamics system has improved, this should become obsolete
            if ((m_angularVelocity.length2() < m_additionalAngularDampingThresholdSqr)
                    && (m_linearVelocity.length2() < m_additionalLinearDampingThresholdSqr)) {
                m_angularVelocity.mulLocal(m_additionalDampingFactor);
                m_linearVelocity.mulLocal(m_additionalDampingFactor);
            }

            double speed = m_linearVelocity.length();
            if (speed < m_linearDamping) {
                double dampVel = 0.005;
                if (speed > dampVel) {
                    btVector3 dir = m_linearVelocity.normalized();
                    m_linearVelocity.subLocal(dir.mul(dampVel));
                } else {
                    m_linearVelocity.setValue(0., 0., 0.);
                }
            }

            double angSpeed = m_angularVelocity.length();
            if (angSpeed < m_angularDamping) {
                double angDampVel = 0.005;
                if (angSpeed > angDampVel) {
                    btVector3 dir = m_angularVelocity.normalized();
                    m_angularVelocity.subLocal(dir.mul(angDampVel));
                } else {
                    m_angularVelocity.setValue(0., 0., 0.);
                }
            }
        }
    }

    // getCollisionShape(): identical inline redeclaration of btCollisionObject::getCollisionShape
    // (inherited).

    public void setMassProps(double mass, btVector3 inertia) {
        if (mass == 0.) {
            m_collisionFlags |= btCollisionObject.CF_STATIC_OBJECT;
            m_inverseMass = 0.;
        } else {
            m_collisionFlags &= (~btCollisionObject.CF_STATIC_OBJECT);
            m_inverseMass = 1.0 / mass;
        }

        // Fg = m * a
        m_gravity.set(m_gravity_acceleration.mul(mass));

        m_invInertiaLocal.setValue(
                inertia.x() != 0.0 ? 1.0 / inertia.x() : 0.0,
                inertia.y() != 0.0 ? 1.0 / inertia.y() : 0.0,
                inertia.z() != 0.0 ? 1.0 / inertia.z() : 0.0);

        m_invMass.set(m_linearFactor.mul(m_inverseMass));
    }

    public btVector3 getLinearFactor() {
        return m_linearFactor;
    }

    public void setLinearFactor(btVector3 linearFactor) {
        m_linearFactor.set(linearFactor);
        m_invMass.set(m_linearFactor.mul(m_inverseMass));
    }

    public double getInvMass() {
        return m_inverseMass;
    }

    public btMatrix3x3 getInvInertiaTensorWorld() {
        return m_invInertiaTensorWorld;
    }

    public void integrateVelocities(double step) {
        if (isStaticOrKinematicObject()) {
            return;
        }

        m_linearVelocity.addLocal(m_totalForce.mul(m_inverseMass * step));
        m_angularVelocity.addLocal(m_invInertiaTensorWorld.mul(m_totalTorque).mul(step));

        // #define MAX_ANGVEL SIMD_HALF_PI
        // clamp angular velocity. collision calculations will fail on higher angular velocities
        double angvel = m_angularVelocity.length();
        if (angvel * step > btScalar.SIMD_HALF_PI) {
            m_angularVelocity.mulLocal((btScalar.SIMD_HALF_PI / step) / angvel);
        }
    }

    public void setCenterOfMassTransform(btTransform xform) {
        if (isKinematicObject()) {
            m_interpolationWorldTransform.set(m_worldTransform);
        } else {
            m_interpolationWorldTransform.set(xform);
        }
        m_interpolationLinearVelocity.set(getLinearVelocity());
        m_interpolationAngularVelocity.set(getAngularVelocity());
        m_worldTransform.set(xform);
        updateInertiaTensor();
    }

    public void applyCentralForce(btVector3 force) {
        m_totalForce.addLocal(force.mul(m_linearFactor));
    }

    public btVector3 getTotalForce() {
        return m_totalForce;
    }

    public btVector3 getTotalTorque() {
        return m_totalTorque;
    }

    public btVector3 getInvInertiaDiagLocal() {
        return m_invInertiaLocal;
    }

    public void setInvInertiaDiagLocal(btVector3 diagInvInertia) {
        m_invInertiaLocal.set(diagInvInertia);
    }

    public void setSleepingThresholds(double linear, double angular) {
        m_linearSleepingThreshold = linear;
        m_angularSleepingThreshold = angular;
    }

    public void applyTorque(btVector3 torque) {
        m_totalTorque.addLocal(torque.mul(m_angularFactor));
    }

    public void applyForce(btVector3 force, btVector3 rel_pos) {
        applyCentralForce(force);
        applyTorque(rel_pos.cross(force.mul(m_linearFactor)));
    }

    public void applyCentralImpulse(btVector3 impulse) {
        m_linearVelocity.addLocal(impulse.mul(m_linearFactor).mul(m_inverseMass));
    }

    public void applyTorqueImpulse(btVector3 torque) {
        m_angularVelocity.addLocal(m_invInertiaTensorWorld.mul(torque).mul(m_angularFactor));
    }

    public void applyImpulse(btVector3 impulse, btVector3 rel_pos) {
        if (m_inverseMass != 0.) {
            applyCentralImpulse(impulse);
            // C++ `if (m_angularFactor)`: btVector3 converts to btScalar* (&m_floats[0]), never
            // null.
            applyTorqueImpulse(rel_pos.cross(impulse.mul(m_linearFactor)));
        }
    }

    public void clearForces() {
        m_totalForce.setValue(0.0, 0.0, 0.0);
        m_totalTorque.setValue(0.0, 0.0, 0.0);
    }

    public void updateInertiaTensor() {
        m_invInertiaTensorWorld.set(
                m_worldTransform
                        .getBasis()
                        .scaled(m_invInertiaLocal)
                        .mul(m_worldTransform.getBasis().transpose()));
    }

    public btVector3 getCenterOfMassPosition() {
        return m_worldTransform.getOrigin();
    }

    public btQuaternion getOrientation() {
        btQuaternion orn = new btQuaternion();
        m_worldTransform.getBasis().getRotation(orn);
        return orn;
    }

    public btTransform getCenterOfMassTransform() {
        return m_worldTransform;
    }

    public btVector3 getLinearVelocity() {
        return m_linearVelocity;
    }

    public btVector3 getAngularVelocity() {
        return m_angularVelocity;
    }

    public void setLinearVelocity(btVector3 lin_vel) {
        m_updateRevision++;
        m_linearVelocity.set(lin_vel);
    }

    public void setAngularVelocity(btVector3 ang_vel) {
        m_updateRevision++;
        m_angularVelocity.set(ang_vel);
    }

    public btVector3 getVelocityInLocalPoint(btVector3 rel_pos) {
        // we also calculate lin/ang velocity for kinematic objects
        return m_linearVelocity.add(m_angularVelocity.cross(rel_pos));
    }

    public void translate(btVector3 v) {
        m_worldTransform.getOrigin().addLocal(v);
    }

    public void getAabb(btVector3 aabbMin, btVector3 aabbMax) {
        getCollisionShape().getAabb(m_worldTransform, aabbMin, aabbMax);
    }

    public double computeImpulseDenominator(btVector3 pos, btVector3 normal) {
        btVector3 r0 = pos.sub(getCenterOfMassPosition());
        btVector3 c0 = (r0).cross(normal);
        btVector3 vec = btMatrix3x3.mul(c0, getInvInertiaTensorWorld()).cross(r0);
        return m_inverseMass + normal.dot(vec);
    }

    public double computeAngularImpulseDenominator(btVector3 axis) {
        btVector3 vec = btMatrix3x3.mul(axis, getInvInertiaTensorWorld());
        return axis.dot(vec);
    }

    public void updateDeactivation(double timeStep) {
        if ((getActivationState() == ISLAND_SLEEPING)
                || (getActivationState() == DISABLE_DEACTIVATION)) {
            return;
        }

        if ((getLinearVelocity().length2() < m_linearSleepingThreshold * m_linearSleepingThreshold)
                && (getAngularVelocity().length2()
                        < m_angularSleepingThreshold * m_angularSleepingThreshold)) {
            m_deactivationTime += timeStep;
        } else {
            m_deactivationTime = 0.;
            setActivationState(0);
        }
    }

    public boolean wantsSleeping() {
        if (getActivationState() == DISABLE_DEACTIVATION) {
            return false;
        }

        // disable deactivation
        if (btGlobals.gDisableDeactivation || (btGlobals.gDeactivationTime == 0.)) {
            return false;
        }

        if ((getActivationState() == ISLAND_SLEEPING)
                || (getActivationState() == WANTS_DEACTIVATION)) {
            return true;
        }

        if (m_deactivationTime > btGlobals.gDeactivationTime) {
            return true;
        }
        return false;
    }

    public btBroadphaseProxy getBroadphaseProxy() {
        return m_broadphaseHandle;
    }

    public void setNewBroadphaseProxy(btBroadphaseProxy broadphaseProxy) {
        m_broadphaseHandle = broadphaseProxy;
    }

    public btMotionState getMotionState() {
        return m_optionalMotionState;
    }

    public void setMotionState(btMotionState motionState) {
        m_optionalMotionState = motionState;
        if (m_optionalMotionState != null) {
            motionState.getWorldTransform(m_worldTransform);
        }
    }

    public void setAngularFactor(btVector3 angFac) {
        m_updateRevision++;
        m_angularFactor.set(angFac);
    }

    public void setAngularFactor(double angFac) {
        m_updateRevision++;
        m_angularFactor.setValue(angFac, angFac, angFac);
    }

    public btVector3 getAngularFactor() {
        return m_angularFactor;
    }

    /** is this rigidbody added to a btCollisionWorld/btDynamicsWorld/btBroadphase? */
    public boolean isInWorld() {
        return (getBroadphaseProxy() != null);
    }

    @Override
    public boolean checkCollideWithOverride(btCollisionObject co) {
        btRigidBody otherRb = btRigidBody.upcast(co);
        if (otherRb == null) {
            return true;
        }

        for (int i = 0; i < m_constraintRefs.size(); ++i) {
            btTypedConstraint c = m_constraintRefs.get(i);
            if (c.isEnabled()) {
                if (c.getRigidBodyA() == otherRb || c.getRigidBodyB() == otherRb) {
                    return false;
                }
            }
        }

        return true;
    }

    public void addConstraintRef(btTypedConstraint c) {
        int index = m_constraintRefs.findLinearSearch(c);
        if (index == m_constraintRefs.size()) {
            m_constraintRefs.push_back(c);
        }

        m_checkCollideWith = 1; // C++: int m_checkCollideWith = true;
    }

    public void removeConstraintRef(btTypedConstraint c) {
        m_constraintRefs.remove(c);
        m_checkCollideWith = m_constraintRefs.size() > 0 ? 1 : 0;
    }

    public btTypedConstraint getConstraintRef(int index) {
        return m_constraintRefs.get(index);
    }

    public int getNumConstraintRefs() {
        return m_constraintRefs.size();
    }

    public void setFlags(int flags) {
        m_rigidbodyFlags = flags;
    }

    public int getFlags() {
        return m_rigidbodyFlags;
    }

    public btVector3 computeGyroscopicForce(double maxGyroscopicForce) {
        btVector3 inertiaLocal = new btVector3();
        inertiaLocal.set(0, (double) 1.f / getInvInertiaDiagLocal().get(0));
        inertiaLocal.set(1, (double) 1.f / getInvInertiaDiagLocal().get(1));
        inertiaLocal.set(2, (double) 1.f / getInvInertiaDiagLocal().get(2));
        btMatrix3x3 inertiaTensorWorld =
                getWorldTransform()
                        .getBasis()
                        .scaled(inertiaLocal)
                        .mul(getWorldTransform().getBasis().transpose());
        btVector3 tmp = inertiaTensorWorld.mul(getAngularVelocity());
        btVector3 gf = getAngularVelocity().cross(tmp);
        double l2 = gf.length2();
        if (l2 > maxGyroscopicForce * maxGyroscopicForce) {
            gf.mulLocal(1. / btScalar.btSqrt(l2) * maxGyroscopicForce);
        }
        return gf;
    }

    // Serialization (calculateSerializeBufferSize / serialize / serializeSingleObject) is linked in
    // the .so but only reachable through btSerializer, which is not ported (see linearmath.md).
}
