// Port of btDynamicsWorld.h (Bullet 2.82) — header-only class.
package io.pzstorm.storm.bullet.dynamics;

import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseInterface;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionConfiguration;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btConstraintSolver;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btContactSolverInfo;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btTypedConstraint;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The interface class for several dynamics implementation, basic, discrete, parallel, and
 * continuous etc.
 */
public abstract class btDynamicsWorld extends btCollisionWorld {

    // enum btDynamicsWorldType
    public static final int BT_SIMPLE_DYNAMICS_WORLD = 1;
    public static final int BT_DISCRETE_DYNAMICS_WORLD = 2;
    public static final int BT_CONTINUOUS_DYNAMICS_WORLD = 3;
    public static final int BT_SOFT_RIGID_DYNAMICS_WORLD = 4;
    public static final int BT_GPU_DYNAMICS_WORLD = 5;

    /**
     * {@code typedef void (*btInternalTickCallback)(btDynamicsWorld *world, btScalar timeStep);}
     */
    @FunctionalInterface
    public interface btInternalTickCallback {
        void invoke(btDynamicsWorld world, double timeStep);
    }

    public btInternalTickCallback m_internalTickCallback;
    public btInternalTickCallback m_internalPreTickCallback;
    public Object m_worldUserInfo;
    public final btContactSolverInfo m_solverInfo = new btContactSolverInfo();

    public btDynamicsWorld(
            btDispatcher dispatcher,
            btBroadphaseInterface broadphase,
            btCollisionConfiguration collisionConfiguration) {
        super(dispatcher, broadphase, collisionConfiguration);
        m_internalTickCallback = null;
        m_internalPreTickCallback = null;
        m_worldUserInfo = null;
    }

    public abstract int stepSimulation(double timeStep, int maxSubSteps, double fixedTimeStep);

    /** Default arguments: maxSubSteps=1, fixedTimeStep=btScalar(1.)/btScalar(60.). */
    public final int stepSimulation(double timeStep) {
        return stepSimulation(timeStep, 1, 1.0 / 60.0);
    }

    /** Default argument: fixedTimeStep=btScalar(1.)/btScalar(60.). */
    public final int stepSimulation(double timeStep, int maxSubSteps) {
        return stepSimulation(timeStep, maxSubSteps, 1.0 / 60.0);
    }

    // C++ re-declares debugDrawWorld() pure virtual here; not re-declared in Java so that
    // btDiscreteDynamicsWorld can call btCollisionWorld::debugDrawWorld() via super.

    public void addConstraint(
            btTypedConstraint constraint, boolean disableCollisionsBetweenLinkedBodies) {}

    /** Default argument: disableCollisionsBetweenLinkedBodies=false. */
    public final void addConstraint(btTypedConstraint constraint) {
        addConstraint(constraint, false);
    }

    public void removeConstraint(btTypedConstraint constraint) {}

    public abstract void addAction(btActionInterface action);

    public abstract void removeAction(btActionInterface action);

    public abstract void setGravity(btVector3 gravity);

    /** Returns by value (a new object). */
    public abstract btVector3 getGravity();

    public abstract void synchronizeMotionStates();

    public abstract void addRigidBody(btRigidBody body);

    public abstract void addRigidBody(btRigidBody body, short group, short mask);

    public abstract void removeRigidBody(btRigidBody body);

    public abstract void setConstraintSolver(btConstraintSolver solver);

    public abstract btConstraintSolver getConstraintSolver();

    public int getNumConstraints() {
        return 0;
    }

    public btTypedConstraint getConstraint(int index) {
        return null;
    }

    /** Returns a btDynamicsWorldType constant. */
    public abstract int getWorldType();

    public abstract void clearForces();

    /**
     * Set the callback for when an internal tick (simulation substep) happens, optional user info.
     */
    public void setInternalTickCallback(
            btInternalTickCallback cb, Object worldUserInfo, boolean isPreTick) {
        if (isPreTick) {
            m_internalPreTickCallback = cb;
        } else {
            m_internalTickCallback = cb;
        }
        m_worldUserInfo = worldUserInfo;
    }

    /** Default arguments: worldUserInfo=0, isPreTick=false. */
    public void setInternalTickCallback(btInternalTickCallback cb) {
        setInternalTickCallback(cb, null, false);
    }

    /** Default argument: isPreTick=false. */
    public void setInternalTickCallback(btInternalTickCallback cb, Object worldUserInfo) {
        setInternalTickCallback(cb, worldUserInfo, false);
    }

    public void setWorldUserInfo(Object worldUserInfo) {
        m_worldUserInfo = worldUserInfo;
    }

    public Object getWorldUserInfo() {
        return m_worldUserInfo;
    }

    public btContactSolverInfo getSolverInfo() {
        return m_solverInfo;
    }

    /** obsolete, use addAction instead. */
    public void addVehicle(btActionInterface vehicle) {}

    /** obsolete, use removeAction instead. */
    public void removeVehicle(btActionInterface vehicle) {}

    /** obsolete, use addAction instead. */
    public void addCharacter(btActionInterface character) {}

    /** obsolete, use removeAction instead. */
    public void removeCharacter(btActionInterface character) {}
}
