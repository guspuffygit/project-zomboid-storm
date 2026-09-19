// Port of BulletCollision/CollisionDispatch/btCollisionObject.h/.cpp (Bullet 2.82).
package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseProxy;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btCollisionObject. All fields are public (C++ protected members are accessed by subclasses and by
 * PZ glue through the inline accessors).
 *
 * <p>Deviations: the C++ anonymous union {@code {void* m_userObjectPointer; int m_userIndex;}} is
 * two separate fields here (PZ only uses the pointer view). The serializer entry points ({@code
 * calculateSerializeBufferSize}, {@code serialize}, {@code serializeSingleObject}) are not ported
 * (btSerializer is not part of the port). The C++ constructor leaves {@code
 * m_interpolationWorldTransform} and the interpolation velocities uninitialised; Java zeroes them.
 */
public class btCollisionObject {
    // island management, m_activationState1 (#defines in btCollisionObject.h)
    public static final int ACTIVE_TAG = 1;
    public static final int ISLAND_SLEEPING = 2;
    public static final int WANTS_DEACTIVATION = 3;
    public static final int DISABLE_DEACTIVATION = 4;
    public static final int DISABLE_SIMULATION = 5;

    // enum CollisionFlags
    public static final int CF_STATIC_OBJECT = 1;
    public static final int CF_KINEMATIC_OBJECT = 2;
    public static final int CF_NO_CONTACT_RESPONSE = 4;
    public static final int CF_CUSTOM_MATERIAL_CALLBACK = 8;
    public static final int CF_CHARACTER_OBJECT = 16;
    public static final int CF_DISABLE_VISUALIZE_OBJECT = 32;
    public static final int CF_DISABLE_SPU_COLLISION_PROCESSING = 64;

    // enum CollisionObjectTypes
    public static final int CO_COLLISION_OBJECT = 1;
    public static final int CO_RIGID_BODY = 2;
    public static final int CO_GHOST_OBJECT = 4;
    public static final int CO_SOFT_BODY = 8;
    public static final int CO_HF_FLUID = 16;
    public static final int CO_USER_TYPE = 32;
    public static final int CO_FEATHERSTONE_LINK = 64;

    // enum AnisotropicFrictionFlags
    public static final int CF_ANISOTROPIC_FRICTION_DISABLED = 0;
    public static final int CF_ANISOTROPIC_FRICTION = 1;
    public static final int CF_ANISOTROPIC_ROLLING_FRICTION = 2;

    public final btTransform m_worldTransform = new btTransform();

    /**
     * m_interpolationWorldTransform is used for CCD and interpolation it can be either previous or
     * future (predicted) transform
     */
    public final btTransform m_interpolationWorldTransform = new btTransform();

    public final btVector3 m_interpolationLinearVelocity = new btVector3();
    public final btVector3 m_interpolationAngularVelocity = new btVector3();

    public final btVector3 m_anisotropicFriction = new btVector3();
    public int m_hasAnisotropicFriction;
    public double m_contactProcessingThreshold;

    public btBroadphaseProxy m_broadphaseHandle;
    public btCollisionShape m_collisionShape;

    /** m_extensionPointer is used by some internal low-level Bullet extensions. */
    public Object m_extensionPointer;

    /** m_rootCollisionShape is temporarily used to store the original collision shape. */
    public btCollisionShape m_rootCollisionShape;

    public int m_collisionFlags;

    public int m_islandTag1;
    public int m_companionId;

    public int m_activationState1;
    public double m_deactivationTime;

    public double m_friction;
    public double m_restitution;
    public double m_rollingFriction;

    /** m_internalType is reserved to distinguish Bullet's btCollisionObject, btRigidBody, ... */
    public int m_internalType;

    /** union member (pointer view). */
    public Object m_userObjectPointer;

    /** union member (int view); kept as a separate field, see class comment. */
    public int m_userIndex;

    /** time of impact calculation */
    public double m_hitFraction;

    /** Swept sphere radius (0.0 by default), see btConvexConvexAlgorithm:: */
    public double m_ccdSweptSphereRadius;

    /** Don't do continuous collision detection if the motion (in one step) is less then this */
    public double m_ccdMotionThreshold;

    /** If some object should have elaborate collision filtering by sub-classes */
    public int m_checkCollideWith;

    /** internal update revision number. It will be increased when the object changes. */
    public int m_updateRevision;

    public btCollisionObject() {
        m_anisotropicFriction.setValue((double) 1.f, (double) 1.f, (double) 1.f);
        m_hasAnisotropicFriction = 0;
        m_contactProcessingThreshold = btScalar.BT_LARGE_FLOAT;
        m_broadphaseHandle = null;
        m_collisionShape = null;
        m_extensionPointer = null;
        m_rootCollisionShape = null;
        m_collisionFlags = CF_STATIC_OBJECT;
        m_islandTag1 = -1;
        m_companionId = -1;
        m_activationState1 = 1;
        m_deactivationTime = 0.0;
        m_friction = 0.5;
        m_rollingFriction = (double) 0.0f;
        m_restitution = 0.0;
        m_internalType = CO_COLLISION_OBJECT;
        m_userObjectPointer = null;
        m_hitFraction = 1.0;
        m_ccdSweptSphereRadius = 0.0;
        m_ccdMotionThreshold = 0.0;
        m_checkCollideWith = 0;
        m_updateRevision = 0;
        m_worldTransform.setIdentity();
    }

    protected boolean checkCollideWithOverride(btCollisionObject co) {
        return true;
    }

    public boolean mergesSimulationIslands() {
        /// static objects, kinematic and object without contact response don't merge islands
        return ((m_collisionFlags
                        & (CF_STATIC_OBJECT | CF_KINEMATIC_OBJECT | CF_NO_CONTACT_RESPONSE))
                == 0);
    }

    public btVector3 getAnisotropicFriction() {
        return m_anisotropicFriction;
    }

    public void setAnisotropicFriction(btVector3 anisotropicFriction) {
        setAnisotropicFriction(anisotropicFriction, CF_ANISOTROPIC_FRICTION);
    }

    public void setAnisotropicFriction(btVector3 anisotropicFriction, int frictionMode) {
        m_anisotropicFriction.set(anisotropicFriction);
        boolean isUnity =
                (anisotropicFriction.get(0) != 1.f)
                        || (anisotropicFriction.get(1) != 1.f)
                        || (anisotropicFriction.get(2) != 1.f);
        m_hasAnisotropicFriction = isUnity ? frictionMode : 0;
    }

    public boolean hasAnisotropicFriction() {
        return hasAnisotropicFriction(CF_ANISOTROPIC_FRICTION);
    }

    public boolean hasAnisotropicFriction(int frictionMode) {
        return (m_hasAnisotropicFriction & frictionMode) != 0;
    }

    /**
     * the constraint solver can discard solving contacts, if the distance is above this threshold.
     * 0 by default.
     */
    public void setContactProcessingThreshold(double contactProcessingThreshold) {
        m_contactProcessingThreshold = contactProcessingThreshold;
    }

    public double getContactProcessingThreshold() {
        return m_contactProcessingThreshold;
    }

    public boolean isStaticObject() {
        return (m_collisionFlags & CF_STATIC_OBJECT) != 0;
    }

    public boolean isKinematicObject() {
        return (m_collisionFlags & CF_KINEMATIC_OBJECT) != 0;
    }

    public boolean isStaticOrKinematicObject() {
        return (m_collisionFlags & (CF_KINEMATIC_OBJECT | CF_STATIC_OBJECT)) != 0;
    }

    public boolean hasContactResponse() {
        return (m_collisionFlags & CF_NO_CONTACT_RESPONSE) == 0;
    }

    public void setCollisionShape(btCollisionShape collisionShape) {
        m_updateRevision++;
        m_collisionShape = collisionShape;
        m_rootCollisionShape = collisionShape;
    }

    public btCollisionShape getCollisionShape() {
        return m_collisionShape;
    }

    /**
     * Avoid using this internal API call, the extension pointer is used by some Bullet extensions.
     */
    public Object internalGetExtensionPointer() {
        return m_extensionPointer;
    }

    /**
     * Avoid using this internal API call, the extension pointer is used by some Bullet extensions.
     */
    public void internalSetExtensionPointer(Object pointer) {
        m_extensionPointer = pointer;
    }

    public int getActivationState() {
        return m_activationState1;
    }

    public void setActivationState(int newState) {
        if ((m_activationState1 != DISABLE_DEACTIVATION)
                && (m_activationState1 != DISABLE_SIMULATION)) m_activationState1 = newState;
    }

    public void setDeactivationTime(double time) {
        m_deactivationTime = time;
    }

    public double getDeactivationTime() {
        return m_deactivationTime;
    }

    public void forceActivationState(int newState) {
        m_activationState1 = newState;
    }

    public void activate() {
        activate(false);
    }

    public void activate(boolean forceActivation) {
        if (forceActivation || (m_collisionFlags & (CF_STATIC_OBJECT | CF_KINEMATIC_OBJECT)) == 0) {
            setActivationState(ACTIVE_TAG);
            m_deactivationTime = 0.0;
        }
    }

    public boolean isActive() {
        return ((getActivationState() != ISLAND_SLEEPING)
                && (getActivationState() != DISABLE_SIMULATION));
    }

    public void setRestitution(double rest) {
        m_updateRevision++;
        m_restitution = rest;
    }

    public double getRestitution() {
        return m_restitution;
    }

    public void setFriction(double frict) {
        m_updateRevision++;
        m_friction = frict;
    }

    public double getFriction() {
        return m_friction;
    }

    public void setRollingFriction(double frict) {
        m_updateRevision++;
        m_rollingFriction = frict;
    }

    public double getRollingFriction() {
        return m_rollingFriction;
    }

    /** reserved for Bullet internal usage */
    public int getInternalType() {
        return m_internalType;
    }

    public btTransform getWorldTransform() {
        return m_worldTransform;
    }

    public void setWorldTransform(btTransform worldTrans) {
        m_updateRevision++;
        m_worldTransform.set(worldTrans);
    }

    public btBroadphaseProxy getBroadphaseHandle() {
        return m_broadphaseHandle;
    }

    public void setBroadphaseHandle(btBroadphaseProxy handle) {
        m_broadphaseHandle = handle;
    }

    public btTransform getInterpolationWorldTransform() {
        return m_interpolationWorldTransform;
    }

    public void setInterpolationWorldTransform(btTransform trans) {
        m_updateRevision++;
        m_interpolationWorldTransform.set(trans);
    }

    public void setInterpolationLinearVelocity(btVector3 linvel) {
        m_updateRevision++;
        m_interpolationLinearVelocity.set(linvel);
    }

    public void setInterpolationAngularVelocity(btVector3 angvel) {
        m_updateRevision++;
        m_interpolationAngularVelocity.set(angvel);
    }

    public btVector3 getInterpolationLinearVelocity() {
        return m_interpolationLinearVelocity;
    }

    public btVector3 getInterpolationAngularVelocity() {
        return m_interpolationAngularVelocity;
    }

    public int getIslandTag() {
        return m_islandTag1;
    }

    public void setIslandTag(int tag) {
        m_islandTag1 = tag;
    }

    public int getCompanionId() {
        return m_companionId;
    }

    public void setCompanionId(int id) {
        m_companionId = id;
    }

    public double getHitFraction() {
        return m_hitFraction;
    }

    public void setHitFraction(double hitFraction) {
        m_hitFraction = hitFraction;
    }

    public int getCollisionFlags() {
        return m_collisionFlags;
    }

    public void setCollisionFlags(int flags) {
        m_collisionFlags = flags;
    }

    /** Swept sphere radius (0.0 by default), see btConvexConvexAlgorithm:: */
    public double getCcdSweptSphereRadius() {
        return m_ccdSweptSphereRadius;
    }

    /** Swept sphere radius (0.0 by default), see btConvexConvexAlgorithm:: */
    public void setCcdSweptSphereRadius(double radius) {
        m_ccdSweptSphereRadius = radius;
    }

    public double getCcdMotionThreshold() {
        return m_ccdMotionThreshold;
    }

    public double getCcdSquareMotionThreshold() {
        return m_ccdMotionThreshold * m_ccdMotionThreshold;
    }

    /** Don't do continuous collision detection if the motion (in one step) is less then this */
    public void setCcdMotionThreshold(double ccdMotionThreshold) {
        m_ccdMotionThreshold = ccdMotionThreshold;
    }

    /** users can point to their objects, userPointer is not used by Bullet */
    public Object getUserPointer() {
        return m_userObjectPointer;
    }

    public int getUserIndex() {
        return m_userIndex;
    }

    /** users can point to their objects, userPointer is not used by Bullet */
    public void setUserPointer(Object userPointer) {
        m_userObjectPointer = userPointer;
    }

    /** users can point to their objects, userPointer is not used by Bullet */
    public void setUserIndex(int index) {
        m_userIndex = index;
    }

    public int getUpdateRevisionInternal() {
        return m_updateRevision;
    }

    public boolean checkCollideWith(btCollisionObject co) {
        if (m_checkCollideWith != 0) return checkCollideWithOverride(co);

        return true;
    }
}
