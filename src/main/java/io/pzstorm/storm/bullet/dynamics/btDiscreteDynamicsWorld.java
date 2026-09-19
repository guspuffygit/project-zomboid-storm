// Port of btDiscreteDynamicsWorld.cpp (Bullet 2.82) + btDiscreteDynamicsWorld.h.
// Serialization (serialize / serializeRigidBodies / serializeDynamicsWorldInfo) is linked in the
// .so but
// only reachable through btSerializer, which is not ported (see docs/re-bullet/linearmath.md).
package io.pzstorm.storm.bullet.dynamics;

import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseInterface;
import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseProxy;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btOverlappingPairCache;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionConfiguration;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.collision.dispatch.btManifoldResult;
import io.pzstorm.storm.bullet.collision.dispatch.btSimulationIslandManager;
import io.pzstorm.storm.bullet.collision.narrowphase.btManifoldPoint;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btConeTwistConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btConstraintSolver;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btContactSolverInfo;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btGeneric6DofConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btHingeConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btPoint2PointConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btSequentialImpulseConstraintSolver;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btTypedConstraint;
import io.pzstorm.storm.bullet.linearmath.CProfileManager;
import io.pzstorm.storm.bullet.linearmath.CProfileSample;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btTransformUtil;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btDiscreteDynamicsWorld provides discrete rigid body simulation; those classes replace the
 * obsolete CcdPhysicsEnvironment/CcdPhysicsController.
 */
public class btDiscreteDynamicsWorld extends btDynamicsWorld {

    /**
     * sizeof(btSequentialImpulseConstraintSolver) in the .so (btAlignedAllocInternal(0x138,0x10)).
     */
    static final int SIZEOF_btSequentialImpulseConstraintSolver = 0x138;

    /** sizeof(btSimulationIslandManager) in the .so (btAlignedAllocInternal(0x70,0x10)). */
    static final int SIZEOF_btSimulationIslandManager = 0x70;

    /** sizeof(InplaceSolverIslandCallback) in the .so (btAlignedAllocInternal(0x98,0x10)). */
    static final int SIZEOF_InplaceSolverIslandCallback = 0x98;

    // protected:
    public final btAlignedObjectArray<btTypedConstraint> m_sortedConstraints =
            new btAlignedObjectArray<>();
    public InplaceSolverIslandCallback m_solverIslandCallback;
    public btConstraintSolver m_constraintSolver;
    public btSimulationIslandManager m_islandManager;
    public final btAlignedObjectArray<btTypedConstraint> m_constraints =
            new btAlignedObjectArray<>();
    public final btAlignedObjectArray<btRigidBody> m_nonStaticRigidBodies =
            new btAlignedObjectArray<>();
    public final btVector3 m_gravity = new btVector3(0, -10, 0);

    // for variable timesteps
    public double m_localTime;
    public double m_fixedTimeStep;
    // for variable timesteps

    public boolean m_ownsIslandManager;
    public boolean m_ownsConstraintSolver;
    public boolean m_synchronizeAllMotionStates;
    public boolean m_applySpeculativeContactRestitution;

    public final btAlignedObjectArray<btActionInterface> m_actions = new btAlignedObjectArray<>();

    public int m_profileTimings;

    public boolean m_latencyMotionStateInterpolation;

    /** for continuous collision detection */
    public final btAlignedObjectArray<btPersistentManifold> m_predictiveManifolds =
            new btAlignedObjectArray<>();

    /**
     * Emulated addresses of the three btAlignedAlloc'ed helpers (for btAlignedFree in destroy()).
     */
    private long m_constraintSolverMem;

    private long m_islandManagerMem;
    private long m_solverIslandCallbackMem;

    // ------------------------------------------------------------------------------------------
    // file-scope helpers

    /** {@code SIMD_FORCE_INLINE int btGetConstraintIslandId(const btTypedConstraint* lhs)} */
    public static int btGetConstraintIslandId(btTypedConstraint lhs) {
        int islandId;

        btCollisionObject rcolObj0 = lhs.getRigidBodyA();
        btCollisionObject rcolObj1 = lhs.getRigidBodyB();
        islandId = rcolObj0.getIslandTag() >= 0 ? rcolObj0.getIslandTag() : rcolObj1.getIslandTag();
        return islandId;
    }

    /** {@code class btSortConstraintOnIslandPredicate} */
    public static final class btSortConstraintOnIslandPredicate
            implements java.util.function.BiPredicate<btTypedConstraint, btTypedConstraint> {
        @Override
        public boolean test(btTypedConstraint lhs, btTypedConstraint rhs) {
            int rIslandId0, lIslandId0;
            rIslandId0 = btGetConstraintIslandId(rhs);
            lIslandId0 = btGetConstraintIslandId(lhs);
            return lIslandId0 < rIslandId0;
        }
    }

    /**
     * {@code struct InplaceSolverIslandCallback : public
     * btSimulationIslandManager::IslandCallback}.
     *
     * <p>C++ {@code T**} pointer-into-array arguments are passed as {@code (Object[] array, int
     * offset)} pairs (array = btAlignedObjectArray.m_data; null where C++ passes 0). {@code
     * m_sortedConstraints} always points at element 0 of the world's m_sortedConstraints (or is 0),
     * so it is stored as the bare {@code Object[]}.
     */
    public static class InplaceSolverIslandCallback
            extends btSimulationIslandManager.IslandCallback {
        public btContactSolverInfo m_solverInfo;
        public btConstraintSolver m_solver;
        public Object[] m_sortedConstraints;
        public int m_numConstraints;
        public btIDebugDraw m_debugDrawer;
        public btDispatcher m_dispatcher;

        public final btAlignedObjectArray<btCollisionObject> m_bodies =
                new btAlignedObjectArray<>();
        public final btAlignedObjectArray<btPersistentManifold> m_manifolds =
                new btAlignedObjectArray<>();
        public final btAlignedObjectArray<btTypedConstraint> m_constraints =
                new btAlignedObjectArray<>();

        /** C++ second parameter {@code btStackAlloc* stackAlloc} is unused (always 0). */
        public InplaceSolverIslandCallback(
                btConstraintSolver solver, Object stackAlloc, btDispatcher dispatcher) {
            m_solverInfo = null;
            m_solver = solver;
            m_sortedConstraints = null;
            m_numConstraints = 0;
            m_debugDrawer = null;
            m_dispatcher = dispatcher;
        }

        public void setup(
                btContactSolverInfo solverInfo,
                Object[] sortedConstraints,
                int numConstraints,
                btIDebugDraw debugDrawer) {
            m_solverInfo = solverInfo;
            m_sortedConstraints = sortedConstraints;
            m_numConstraints = numConstraints;
            m_debugDrawer = debugDrawer;
            m_bodies.resize(0);
            m_manifolds.resize(0);
            m_constraints.resize(0);
        }

        @Override
        public void processIsland(
                Object[] bodies,
                int bodiesOffset,
                int numBodies,
                Object[] manifolds,
                int manifoldsOffset,
                int numManifolds,
                int islandId) {
            if (islandId < 0) {
                /// we don't split islands, so all constraints/contact manifolds/bodies are passed
                // into the solver regardless the island id
                m_solver.solveGroup(
                        bodiesArg(bodies, bodiesOffset, numBodies),
                        numBodies,
                        manifoldsArg(manifolds, manifoldsOffset, numManifolds),
                        numManifolds,
                        constraintsArg(m_sortedConstraints, 0, m_numConstraints),
                        m_numConstraints,
                        m_solverInfo,
                        m_debugDrawer,
                        m_dispatcher);
            } else {
                // also add all non-contact constraints/joints for this island
                Object[] startConstraint = null;
                int startConstraintOffset = 0;
                int numCurConstraints = 0;
                int i;

                // find the first constraint for this island
                for (i = 0; i < m_numConstraints; i++) {
                    if (btGetConstraintIslandId((btTypedConstraint) m_sortedConstraints[i])
                            == islandId) {
                        startConstraint = m_sortedConstraints;
                        startConstraintOffset = i;
                        break;
                    }
                }
                // count the number of constraints in this island
                for (; i < m_numConstraints; i++) {
                    if (btGetConstraintIslandId((btTypedConstraint) m_sortedConstraints[i])
                            == islandId) {
                        numCurConstraints++;
                    }
                }

                if (m_solverInfo.m_minimumSolverBatchSize <= 1) {
                    m_solver.solveGroup(
                            bodiesArg(bodies, bodiesOffset, numBodies),
                            numBodies,
                            manifoldsArg(manifolds, manifoldsOffset, numManifolds),
                            numManifolds,
                            constraintsArg(
                                    startConstraint, startConstraintOffset, numCurConstraints),
                            numCurConstraints,
                            m_solverInfo,
                            m_debugDrawer,
                            m_dispatcher);
                } else {
                    for (i = 0; i < numBodies; i++) {
                        m_bodies.push_back((btCollisionObject) bodies[bodiesOffset + i]);
                    }
                    for (i = 0; i < numManifolds; i++) {
                        m_manifolds.push_back(
                                (btPersistentManifold) manifolds[manifoldsOffset + i]);
                    }
                    for (i = 0; i < numCurConstraints; i++) {
                        m_constraints.push_back(
                                (btTypedConstraint) startConstraint[startConstraintOffset + i]);
                    }
                    if ((m_constraints.size() + m_manifolds.size())
                            > m_solverInfo.m_minimumSolverBatchSize) {
                        processConstraints();
                    } else {
                        // printf("deferred\n");
                    }
                }
            }
        }

        // btConstraintSolver takes typed arrays whose index 0 is the C++ pointer base; a C++
        // {@code base + offset} pointer becomes a copy of that subrange (the solver only reads it),
        // a null pointer stays null.
        static btCollisionObject[] bodiesArg(Object[] arr, int off, int n) {
            if (arr == null) {
                return null;
            }
            btCollisionObject[] r = new btCollisionObject[n];
            for (int i = 0; i < n; i++) {
                r[i] = (btCollisionObject) arr[off + i];
            }
            return r;
        }

        static btPersistentManifold[] manifoldsArg(Object[] arr, int off, int n) {
            if (arr == null) {
                return null;
            }
            btPersistentManifold[] r = new btPersistentManifold[n];
            for (int i = 0; i < n; i++) {
                r[i] = (btPersistentManifold) arr[off + i];
            }
            return r;
        }

        static btTypedConstraint[] constraintsArg(Object[] arr, int off, int n) {
            if (arr == null) {
                return null;
            }
            btTypedConstraint[] r = new btTypedConstraint[n];
            for (int i = 0; i < n; i++) {
                r[i] = (btTypedConstraint) arr[off + i];
            }
            return r;
        }

        public void processConstraints() {
            Object[] bodies = m_bodies.size() != 0 ? m_bodies.m_data : null;
            Object[] manifold = m_manifolds.size() != 0 ? m_manifolds.m_data : null;
            Object[] constraints = m_constraints.size() != 0 ? m_constraints.m_data : null;

            m_solver.solveGroup(
                    bodiesArg(bodies, 0, m_bodies.size()),
                    m_bodies.size(),
                    manifoldsArg(manifold, 0, m_manifolds.size()),
                    m_manifolds.size(),
                    constraintsArg(constraints, 0, m_constraints.size()),
                    m_constraints.size(),
                    m_solverInfo,
                    m_debugDrawer,
                    m_dispatcher);
            m_bodies.resize(0);
            m_manifolds.resize(0);
            m_constraints.resize(0);
        }

        /** Destructor: member arrays are destroyed (btAlignedObjectArray dtor = clear()). */
        @Override
        public void destroy() {
            m_constraints.clear();
            m_manifolds.clear();
            m_bodies.clear();
            super.destroy();
        }
    }

    /**
     * {@code class btClosestNotMeConvexResultCallback : public
     * btCollisionWorld::ClosestConvexResultCallback}
     */
    public static class btClosestNotMeConvexResultCallback
            extends btCollisionWorld.ClosestConvexResultCallback {
        public btCollisionObject m_me;
        public double m_allowedPenetration;
        public btOverlappingPairCache m_pairCache;
        public btDispatcher m_dispatcher;

        public btClosestNotMeConvexResultCallback(
                btCollisionObject me,
                btVector3 fromA,
                btVector3 toA,
                btOverlappingPairCache pairCache,
                btDispatcher dispatcher) {
            super(fromA, toA);
            m_me = me;
            m_allowedPenetration = (double) 0.0f;
            m_pairCache = pairCache;
            m_dispatcher = dispatcher;
        }

        @Override
        public double addSingleResult(
                btCollisionWorld.LocalConvexResult convexResult, boolean normalInWorldSpace) {
            if (convexResult.m_hitCollisionObject == m_me) {
                return (double) 1.0f;
            }

            // ignore result if there is no contact response
            if (!convexResult.m_hitCollisionObject.hasContactResponse()) {
                return (double) 1.0f;
            }

            btVector3 linVelA = new btVector3(), linVelB = new btVector3();
            linVelA.set(m_convexToWorld.sub(m_convexFromWorld));
            linVelB.set(new btVector3(0, 0, 0)); // toB.getOrigin()-fromB.getOrigin();

            btVector3 relativeVelocity = (linVelA.sub(linVelB));
            // don't report time of impact for motion away from the contact normal (or causes minor
            // penetration)
            if (convexResult.m_hitNormalLocal.dot(relativeVelocity) >= -m_allowedPenetration) {
                return (double) 1.f;
            }

            return super.addSingleResult(convexResult, normalInWorldSpace);
        }

        @Override
        public boolean needsCollision(btBroadphaseProxy proxy0) {
            // don't collide with itself
            if (proxy0.m_clientObject == m_me) {
                return false;
            }

            /// don't do CCD when the collision filters are not matching
            if (!super.needsCollision(proxy0)) {
                return false;
            }

            btCollisionObject otherObj = (btCollisionObject) proxy0.m_clientObject;

            // call needsResponse, see http://code.google.com/p/bullet/issues/detail?id=179
            if (m_dispatcher.needsResponse(m_me, otherObj)) {
                return true;
            }

            return false;
        }
    }

    // ------------------------------------------------------------------------------------------

    /**
     * this btDiscreteDynamicsWorld constructor gets created objects from the user, and will not
     * delete those
     */
    public btDiscreteDynamicsWorld(
            btDispatcher dispatcher,
            btBroadphaseInterface pairCache,
            btConstraintSolver constraintSolver,
            btCollisionConfiguration collisionConfiguration) {
        super(dispatcher, pairCache, collisionConfiguration);
        m_solverIslandCallback = null;
        m_constraintSolver = constraintSolver;
        m_localTime = 0;
        m_fixedTimeStep = 0;
        m_synchronizeAllMotionStates = false;
        m_applySpeculativeContactRestitution = false;
        m_profileTimings = 0;
        m_latencyMotionStateInterpolation = true;

        if (m_constraintSolver == null) {
            m_constraintSolverMem =
                    btGlobals.btAlignedAlloc(SIZEOF_btSequentialImpulseConstraintSolver, 16);
            m_constraintSolver = new btSequentialImpulseConstraintSolver();
            m_ownsConstraintSolver = true;
        } else {
            m_ownsConstraintSolver = false;
        }

        {
            m_islandManagerMem = btGlobals.btAlignedAlloc(SIZEOF_btSimulationIslandManager, 16);
            m_islandManager = new btSimulationIslandManager();
        }

        m_ownsIslandManager = true;

        {
            m_solverIslandCallbackMem =
                    btGlobals.btAlignedAlloc(SIZEOF_InplaceSolverIslandCallback, 16);
            m_solverIslandCallback =
                    new InplaceSolverIslandCallback(m_constraintSolver, null, dispatcher);
        }
    }

    /** Destructor ({@code virtual ~btDiscreteDynamicsWorld()}). */
    @Override
    public void destroy() {
        // only delete it when we created it
        if (m_ownsIslandManager) {
            m_islandManager.destroy();
            btGlobals.btAlignedFree(m_islandManagerMem);
        }
        if (m_solverIslandCallback != null) {
            m_solverIslandCallback.destroy();
            btGlobals.btAlignedFree(m_solverIslandCallbackMem);
        }
        if (m_ownsConstraintSolver) {
            m_constraintSolver.destroy();
            btGlobals.btAlignedFree(m_constraintSolverMem);
        }
        // member arrays (reverse declaration order), then base destructors
        m_predictiveManifolds.clear();
        m_actions.clear();
        m_nonStaticRigidBodies.clear();
        m_constraints.clear();
        m_sortedConstraints.clear();
        super.destroy();
    }

    // ------------------------------------------------------------------------------------------

    public void saveKinematicState(double timeStep) {
        /// would like to iterate over m_nonStaticRigidBodies, but unfortunately old API allows to
        /// change the activation state from outside, so we need to iterate over all objects
        for (int i = 0; i < m_collisionObjects.size(); i++) {
            btCollisionObject colObj = m_collisionObjects.get(i);
            btRigidBody body = btRigidBody.upcast(colObj);
            if (body != null && body.getActivationState() != btCollisionObject.ISLAND_SLEEPING) {
                if (body.isKinematicObject()) {
                    // to calculate velocities next frame
                    body.saveKinematicState(timeStep);
                }
            }
        }
    }

    @Override
    public void debugDrawWorld() {
        try (CProfileSample __profile = new CProfileSample("debugDrawWorld")) {
            super.debugDrawWorld();

            boolean drawConstraints = false;
            if (getDebugDrawer() != null) {
                int mode = getDebugDrawer().getDebugMode();
                if ((mode
                                & (btIDebugDraw.DBG_DrawConstraints
                                        | btIDebugDraw.DBG_DrawConstraintLimits))
                        != 0) {
                    drawConstraints = true;
                }
            }
            if (drawConstraints) {
                for (int i = getNumConstraints() - 1; i >= 0; i--) {
                    btTypedConstraint constraint = getConstraint(i);
                    debugDrawConstraint(constraint);
                }
            }

            if (getDebugDrawer() != null
                    && (getDebugDrawer().getDebugMode()
                                    & (btIDebugDraw.DBG_DrawWireframe
                                            | btIDebugDraw.DBG_DrawAabb
                                            | btIDebugDraw.DBG_DrawNormals))
                            != 0) {
                int i;

                if (getDebugDrawer() != null && getDebugDrawer().getDebugMode() != 0) {
                    for (i = 0; i < m_actions.size(); i++) {
                        m_actions.get(i).debugDraw(m_debugDrawer);
                    }
                }
            }
        }
    }

    @Override
    public void clearForces() {
        /// @todo: iterate over awake simulation islands!
        for (int i = 0; i < m_nonStaticRigidBodies.size(); i++) {
            btRigidBody body = m_nonStaticRigidBodies.get(i);
            // need to check if next line is ok
            // it might break backward compatibility (people applying forces on sleeping objects
            // get never cleared and accumulate on wake-up
            body.clearForces();
        }
    }

    /** apply gravity, call this once per timestep */
    public void applyGravity() {
        /// @todo: iterate over awake simulation islands!
        for (int i = 0; i < m_nonStaticRigidBodies.size(); i++) {
            btRigidBody body = m_nonStaticRigidBodies.get(i);
            if (body.isActive()) {
                body.applyGravity();
            }
        }
    }

    public void synchronizeSingleMotionState(btRigidBody body) {
        // we need to call the update at least once, even for sleeping objects
        // otherwise the 'graphics' transform never updates properly
        /// @todo: add 'dirty' flag
        // if (body->getActivationState() != ISLAND_SLEEPING)
        if (body.getMotionState() != null && !body.isStaticOrKinematicObject()) {
            // we need to call the update at least once, even for sleeping objects
            // otherwise the 'graphics' transform never updates properly
            /// @todo: add 'dirty' flag
            {
                btTransform interpolatedTransform = new btTransform();
                btTransformUtil.integrateTransform(
                        body.getInterpolationWorldTransform(),
                        body.getInterpolationLinearVelocity(),
                        body.getInterpolationAngularVelocity(),
                        (m_latencyMotionStateInterpolation && m_fixedTimeStep != 0)
                                ? m_localTime - m_fixedTimeStep
                                : m_localTime * body.getHitFraction(),
                        interpolatedTransform);
                body.getMotionState().setWorldTransform(interpolatedTransform);
            }
        }
    }

    @Override
    public void synchronizeMotionStates() {
        try (CProfileSample __profile = new CProfileSample("synchronizeMotionStates")) {
            if (m_synchronizeAllMotionStates) {
                // iterate  over all collision objects
                for (int i = 0; i < m_collisionObjects.size(); i++) {
                    btCollisionObject colObj = m_collisionObjects.get(i);
                    btRigidBody body = btRigidBody.upcast(colObj);
                    if (body != null) {
                        synchronizeSingleMotionState(body);
                    }
                }
            } else {
                // iterate over all active rigid bodies
                for (int i = 0; i < m_nonStaticRigidBodies.size(); i++) {
                    btRigidBody body = m_nonStaticRigidBodies.get(i);
                    if (body.isActive()) {
                        synchronizeSingleMotionState(body);
                    }
                }
            }
        }
    }

    @Override
    public int stepSimulation(double timeStep, int maxSubSteps, double fixedTimeStep) {
        startProfiling(timeStep);

        try (CProfileSample __profile = new CProfileSample("stepSimulation")) {
            int numSimulationSubSteps = 0;

            if (maxSubSteps != 0) {
                // fixed timestep with interpolation
                m_fixedTimeStep = fixedTimeStep;
                m_localTime += timeStep;
                if (m_localTime >= fixedTimeStep) {
                    numSimulationSubSteps = btScalar.cvttsd2si(m_localTime / fixedTimeStep);
                    m_localTime -= numSimulationSubSteps * fixedTimeStep;
                }
            } else {
                // variable timestep
                fixedTimeStep = timeStep;
                m_localTime = m_latencyMotionStateInterpolation ? 0 : timeStep;
                m_fixedTimeStep = 0;
                if (btScalar.btFuzzyZero(timeStep)) {
                    numSimulationSubSteps = 0;
                    maxSubSteps = 0;
                } else {
                    numSimulationSubSteps = 1;
                    maxSubSteps = 1;
                }
            }

            // process some debugging flags
            if (getDebugDrawer() != null) {
                btIDebugDraw debugDrawer = getDebugDrawer();
                btGlobals.gDisableDeactivation =
                        (debugDrawer.getDebugMode() & btIDebugDraw.DBG_NoDeactivation) != 0;
            }
            if (numSimulationSubSteps != 0) {
                // clamp the number of substeps, to prevent simulation grinding spiralling down to a
                // halt
                int clampedSimulationSteps =
                        (numSimulationSubSteps > maxSubSteps) ? maxSubSteps : numSimulationSubSteps;

                saveKinematicState(fixedTimeStep * clampedSimulationSteps);

                applyGravity();

                for (int i = 0; i < clampedSimulationSteps; i++) {
                    internalSingleStepSimulation(fixedTimeStep);
                    synchronizeMotionStates();
                }
            } else {
                synchronizeMotionStates();
            }

            clearForces();

            CProfileManager.Increment_Frame_Counter();

            return numSimulationSubSteps;
        }
    }

    public void internalSingleStepSimulation(double timeStep) {
        try (CProfileSample __profile = new CProfileSample("internalSingleStepSimulation")) {
            if (null != m_internalPreTickCallback) {
                m_internalPreTickCallback.invoke(this, timeStep);
            }

            /// apply gravity, predict motion
            predictUnconstraintMotion(timeStep);

            btDispatcherInfo dispatchInfo = getDispatchInfo();

            dispatchInfo.m_timeStep = timeStep;
            dispatchInfo.m_stepCount = 0;
            dispatchInfo.m_debugDraw = getDebugDrawer();

            createPredictiveContacts(timeStep);

            /// perform collision detection
            performDiscreteCollisionDetection();

            calculateSimulationIslands();

            getSolverInfo().m_timeStep = timeStep;

            /// solve contact and other joint constraints
            solveConstraints(getSolverInfo());

            /// CallbackTriggers();

            /// integrate transforms
            integrateTransforms(timeStep);

            /// update vehicle simulation
            updateActions(timeStep);

            updateActivationState(timeStep);

            if (null != m_internalTickCallback) {
                m_internalTickCallback.invoke(this, timeStep);
            }
        }
    }

    @Override
    public void setGravity(btVector3 gravity) {
        m_gravity.set(gravity);
        for (int i = 0; i < m_nonStaticRigidBodies.size(); i++) {
            btRigidBody body = m_nonStaticRigidBodies.get(i);
            if (body.isActive() && (body.getFlags() & btRigidBody.BT_DISABLE_WORLD_GRAVITY) == 0) {
                body.setGravity(gravity);
            }
        }
    }

    @Override
    public btVector3 getGravity() {
        return new btVector3(m_gravity);
    }

    @Override
    public void addCollisionObject(
            btCollisionObject collisionObject,
            short collisionFilterGroup,
            short collisionFilterMask) {
        super.addCollisionObject(collisionObject, collisionFilterGroup, collisionFilterMask);
    }

    /**
     * Default arguments: collisionFilterGroup=btBroadphaseProxy::StaticFilter,
     * collisionFilterMask=btBroadphaseProxy::AllFilter ^ btBroadphaseProxy::StaticFilter.
     */
    @Override
    public void addCollisionObject(btCollisionObject collisionObject) {
        addCollisionObject(
                collisionObject,
                (short) btBroadphaseProxy.StaticFilter,
                (short) (btBroadphaseProxy.AllFilter ^ btBroadphaseProxy.StaticFilter));
    }

    /**
     * Default argument: collisionFilterMask=btBroadphaseProxy::AllFilter ^
     * btBroadphaseProxy::StaticFilter.
     */
    @Override
    public void addCollisionObject(btCollisionObject collisionObject, short collisionFilterGroup) {
        addCollisionObject(
                collisionObject,
                collisionFilterGroup,
                (short) (btBroadphaseProxy.AllFilter ^ btBroadphaseProxy.StaticFilter));
    }

    /**
     * removeCollisionObject will first check if it is a rigid body, if so call removeRigidBody
     * otherwise call btCollisionWorld::removeCollisionObject
     */
    @Override
    public void removeCollisionObject(btCollisionObject collisionObject) {
        btRigidBody body = btRigidBody.upcast(collisionObject);
        if (body != null) {
            removeRigidBody(body);
        } else {
            super.removeCollisionObject(collisionObject);
        }
    }

    @Override
    public void removeRigidBody(btRigidBody body) {
        m_nonStaticRigidBodies.remove(body);
        super.removeCollisionObject(body);
    }

    @Override
    public void addRigidBody(btRigidBody body) {
        if (!body.isStaticOrKinematicObject()
                && (body.getFlags() & btRigidBody.BT_DISABLE_WORLD_GRAVITY) == 0) {
            body.setGravity(m_gravity);
        }

        if (body.getCollisionShape() != null) {
            if (!body.isStaticObject()) {
                m_nonStaticRigidBodies.push_back(body);
            } else {
                body.setActivationState(btCollisionObject.ISLAND_SLEEPING);
            }

            boolean isDynamic = !(body.isStaticObject() || body.isKinematicObject());
            short collisionFilterGroup =
                    isDynamic
                            ? (short) btBroadphaseProxy.DefaultFilter
                            : (short) btBroadphaseProxy.StaticFilter;
            short collisionFilterMask =
                    isDynamic
                            ? (short) btBroadphaseProxy.AllFilter
                            : (short)
                                    (btBroadphaseProxy.AllFilter ^ btBroadphaseProxy.StaticFilter);

            addCollisionObject(body, collisionFilterGroup, collisionFilterMask);
        }
    }

    @Override
    public void addRigidBody(btRigidBody body, short group, short mask) {
        if (!body.isStaticOrKinematicObject()
                && (body.getFlags() & btRigidBody.BT_DISABLE_WORLD_GRAVITY) == 0) {
            body.setGravity(m_gravity);
        }

        if (body.getCollisionShape() != null) {
            if (!body.isStaticObject()) {
                m_nonStaticRigidBodies.push_back(body);
            } else {
                body.setActivationState(btCollisionObject.ISLAND_SLEEPING);
            }
            addCollisionObject(body, group, mask);
        }
    }

    public void updateActions(double timeStep) {
        try (CProfileSample __profile = new CProfileSample("updateActions")) {
            for (int i = 0; i < m_actions.size(); i++) {
                m_actions.get(i).updateAction(this, timeStep);
            }
        }
    }

    public void updateActivationState(double timeStep) {
        try (CProfileSample __profile = new CProfileSample("updateActivationState")) {
            for (int i = 0; i < m_nonStaticRigidBodies.size(); i++) {
                btRigidBody body = m_nonStaticRigidBodies.get(i);
                if (body != null) {
                    body.updateDeactivation(timeStep);

                    if (body.wantsSleeping()) {
                        if (body.isStaticOrKinematicObject()) {
                            body.setActivationState(btCollisionObject.ISLAND_SLEEPING);
                        } else {
                            if (body.getActivationState() == btCollisionObject.ACTIVE_TAG) {
                                body.setActivationState(btCollisionObject.WANTS_DEACTIVATION);
                            }
                            if (body.getActivationState() == btCollisionObject.ISLAND_SLEEPING) {
                                body.setAngularVelocity(new btVector3(0, 0, 0));
                                body.setLinearVelocity(new btVector3(0, 0, 0));
                            }
                        }
                    } else {
                        if (body.getActivationState() != btCollisionObject.DISABLE_DEACTIVATION) {
                            body.setActivationState(btCollisionObject.ACTIVE_TAG);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void addConstraint(
            btTypedConstraint constraint, boolean disableCollisionsBetweenLinkedBodies) {
        m_constraints.push_back(constraint);
        if (disableCollisionsBetweenLinkedBodies) {
            constraint.getRigidBodyA().addConstraintRef(constraint);
            constraint.getRigidBodyB().addConstraintRef(constraint);
        }
    }

    @Override
    public void removeConstraint(btTypedConstraint constraint) {
        m_constraints.remove(constraint);
        constraint.getRigidBodyA().removeConstraintRef(constraint);
        constraint.getRigidBodyB().removeConstraintRef(constraint);
    }

    @Override
    public void addAction(btActionInterface action) {
        m_actions.push_back(action);
    }

    @Override
    public void removeAction(btActionInterface action) {
        m_actions.remove(action);
    }

    public btSimulationIslandManager getSimulationIslandManager() {
        return m_islandManager;
    }

    public btCollisionWorld getCollisionWorld() {
        return this;
    }

    @Override
    public void addVehicle(btActionInterface vehicle) {
        addAction(vehicle);
    }

    @Override
    public void removeVehicle(btActionInterface vehicle) {
        removeAction(vehicle);
    }

    @Override
    public void addCharacter(btActionInterface character) {
        addAction(character);
    }

    @Override
    public void removeCharacter(btActionInterface character) {
        removeAction(character);
    }

    public void solveConstraints(btContactSolverInfo solverInfo) {
        try (CProfileSample __profile = new CProfileSample("solveConstraints")) {
            // sorted version of all btTypedConstraint, based on islandId
            m_sortedConstraints.resize(m_constraints.size());
            int i;
            for (i = 0; i < getNumConstraints(); i++) {
                m_sortedConstraints.set(i, m_constraints.get(i));
            }

            //	btAssert(0);

            m_sortedConstraints.quickSort(new btSortConstraintOnIslandPredicate());

            Object[] constraintsPtr = getNumConstraints() != 0 ? m_sortedConstraints.m_data : null;

            m_solverIslandCallback.setup(
                    solverInfo, constraintsPtr, m_sortedConstraints.size(), getDebugDrawer());
            m_constraintSolver.prepareSolve(
                    getCollisionWorld().getNumCollisionObjects(),
                    getCollisionWorld().getDispatcher().getNumManifolds());

            /// solve all the constraints for this island
            m_islandManager.buildAndProcessIslands(
                    getCollisionWorld().getDispatcher(),
                    getCollisionWorld(),
                    m_solverIslandCallback);

            m_solverIslandCallback.processConstraints();

            m_constraintSolver.allSolved(solverInfo, m_debugDrawer);
        }
    }

    public void calculateSimulationIslands() {
        try (CProfileSample __profile = new CProfileSample("calculateSimulationIslands")) {
            getSimulationIslandManager()
                    .updateActivationState(
                            getCollisionWorld(), getCollisionWorld().getDispatcher());

            {
                // merge islands based on speculative contact manifolds too
                for (int i = 0; i < this.m_predictiveManifolds.size(); i++) {
                    btPersistentManifold manifold = m_predictiveManifolds.get(i);

                    btCollisionObject colObj0 = manifold.getBody0();
                    btCollisionObject colObj1 = manifold.getBody1();

                    if (((colObj0 != null) && (!(colObj0).isStaticOrKinematicObject()))
                            && ((colObj1 != null) && (!(colObj1).isStaticOrKinematicObject()))) {
                        getSimulationIslandManager()
                                .getUnionFind()
                                .unite((colObj0).getIslandTag(), (colObj1).getIslandTag());
                    }
                }
            }

            {
                int i;
                int numConstraints = m_constraints.size();
                for (i = 0; i < numConstraints; i++) {
                    btTypedConstraint constraint = m_constraints.get(i);
                    if (constraint.isEnabled()) {
                        btRigidBody colObj0 = constraint.getRigidBodyA();
                        btRigidBody colObj1 = constraint.getRigidBodyB();

                        if (((colObj0 != null) && (!(colObj0).isStaticOrKinematicObject()))
                                && ((colObj1 != null)
                                        && (!(colObj1).isStaticOrKinematicObject()))) {
                            getSimulationIslandManager()
                                    .getUnionFind()
                                    .unite((colObj0).getIslandTag(), (colObj1).getIslandTag());
                        }
                    }
                }
            }

            // Store the island id in each body
            getSimulationIslandManager().storeIslandActivationState(getCollisionWorld());
        }
    }

    public void createPredictiveContacts(double timeStep) {
        try (CProfileSample __profile = new CProfileSample("createPredictiveContacts")) {
            {
                try (CProfileSample __profile2 =
                        new CProfileSample("release predictive contact manifolds")) {
                    for (int i = 0; i < m_predictiveManifolds.size(); i++) {
                        btPersistentManifold manifold = m_predictiveManifolds.get(i);
                        this.m_dispatcher1.releaseManifold(manifold);
                    }
                    m_predictiveManifolds.clear();
                }
            }

            btTransform predictedTrans = new btTransform();
            for (int i = 0; i < m_nonStaticRigidBodies.size(); i++) {
                btRigidBody body = m_nonStaticRigidBodies.get(i);
                body.setHitFraction((double) 1.f);

                if (body.isActive() && (!body.isStaticOrKinematicObject())) {
                    body.predictIntegratedTransform(timeStep, predictedTrans);

                    double squareMotion =
                            (predictedTrans.getOrigin().sub(body.getWorldTransform().getOrigin()))
                                    .length2();

                    if (getDispatchInfo().m_useContinuous
                            && body.getCcdSquareMotionThreshold() != 0
                            && body.getCcdSquareMotionThreshold() < squareMotion) {
                        try (CProfileSample __profile3 =
                                new CProfileSample("predictive convexSweepTest")) {
                            if (body.getCollisionShape().isConvex()) {
                                btGlobals.gNumClampedCcdMotions++;
                                btClosestNotMeConvexResultCallback sweepResults =
                                        new btClosestNotMeConvexResultCallback(
                                                body,
                                                body.getWorldTransform().getOrigin(),
                                                predictedTrans.getOrigin(),
                                                getBroadphase().getOverlappingPairCache(),
                                                getDispatcher());
                                // btConvexShape* convexShape =
                                // static_cast<btConvexShape*>(body->getCollisionShape());
                                btSphereShape tmpSphere =
                                        new btSphereShape(body.getCcdSweptSphereRadius());
                                sweepResults.m_allowedPenetration =
                                        getDispatchInfo().m_allowedCcdPenetration;

                                sweepResults.m_collisionFilterGroup =
                                        body.getBroadphaseProxy().m_collisionFilterGroup;
                                sweepResults.m_collisionFilterMask =
                                        body.getBroadphaseProxy().m_collisionFilterMask;
                                btTransform modifiedPredictedTrans =
                                        new btTransform(predictedTrans);
                                modifiedPredictedTrans.setBasis(
                                        body.getWorldTransform().getBasis());

                                convexSweepTest(
                                        tmpSphere,
                                        body.getWorldTransform(),
                                        modifiedPredictedTrans,
                                        sweepResults);
                                if (sweepResults.hasHit()
                                        && (sweepResults.m_closestHitFraction < (double) 1.f)) {
                                    btVector3 distVec =
                                            (predictedTrans
                                                            .getOrigin()
                                                            .sub(
                                                                    body.getWorldTransform()
                                                                            .getOrigin()))
                                                    .mul(sweepResults.m_closestHitFraction);
                                    double distance =
                                            distVec.dot(sweepResults.m_hitNormalWorld.negate());

                                    btPersistentManifold manifold =
                                            m_dispatcher1.getNewManifold(
                                                    body, sweepResults.m_hitCollisionObject);
                                    m_predictiveManifolds.push_back(manifold);

                                    btVector3 worldPointB =
                                            body.getWorldTransform().getOrigin().add(distVec);
                                    btVector3 localPointB =
                                            sweepResults
                                                    .m_hitCollisionObject
                                                    .getWorldTransform()
                                                    .inverse()
                                                    .mul(worldPointB);

                                    btManifoldPoint newPoint =
                                            new btManifoldPoint(
                                                    new btVector3(0, 0, 0),
                                                    localPointB,
                                                    sweepResults.m_hitNormalWorld,
                                                    distance);

                                    boolean isPredictive = true;
                                    int index = manifold.addManifoldPoint(newPoint, isPredictive);
                                    btManifoldPoint pt = manifold.getContactPoint(index);
                                    pt.m_combinedRestitution = 0;
                                    pt.m_combinedFriction =
                                            btManifoldResult.calculateCombinedFriction(
                                                    body, sweepResults.m_hitCollisionObject);
                                    pt.m_positionWorldOnA.set(body.getWorldTransform().getOrigin());
                                    pt.m_positionWorldOnB.set(worldPointB);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void integrateTransforms(double timeStep) {
        try (CProfileSample __profile = new CProfileSample("integrateTransforms")) {
            btTransform predictedTrans = new btTransform();
            for (int i = 0; i < m_nonStaticRigidBodies.size(); i++) {
                btRigidBody body = m_nonStaticRigidBodies.get(i);
                body.setHitFraction((double) 1.f);

                if (body.isActive() && (!body.isStaticOrKinematicObject())) {
                    body.predictIntegratedTransform(timeStep, predictedTrans);

                    double squareMotion =
                            (predictedTrans.getOrigin().sub(body.getWorldTransform().getOrigin()))
                                    .length2();

                    if (getDispatchInfo().m_useContinuous
                            && body.getCcdSquareMotionThreshold() != 0
                            && body.getCcdSquareMotionThreshold() < squareMotion) {
                        try (CProfileSample __profile2 =
                                new CProfileSample("CCD motion clamping")) {
                            if (body.getCollisionShape().isConvex()) {
                                btGlobals.gNumClampedCcdMotions++;
                                btClosestNotMeConvexResultCallback sweepResults =
                                        new btClosestNotMeConvexResultCallback(
                                                body,
                                                body.getWorldTransform().getOrigin(),
                                                predictedTrans.getOrigin(),
                                                getBroadphase().getOverlappingPairCache(),
                                                getDispatcher());
                                // btConvexShape* convexShape =
                                // static_cast<btConvexShape*>(body->getCollisionShape());
                                btSphereShape tmpSphere =
                                        new btSphereShape(body.getCcdSweptSphereRadius());
                                sweepResults.m_allowedPenetration =
                                        getDispatchInfo().m_allowedCcdPenetration;

                                sweepResults.m_collisionFilterGroup =
                                        body.getBroadphaseProxy().m_collisionFilterGroup;
                                sweepResults.m_collisionFilterMask =
                                        body.getBroadphaseProxy().m_collisionFilterMask;
                                btTransform modifiedPredictedTrans =
                                        new btTransform(predictedTrans);
                                modifiedPredictedTrans.setBasis(
                                        body.getWorldTransform().getBasis());

                                convexSweepTest(
                                        tmpSphere,
                                        body.getWorldTransform(),
                                        modifiedPredictedTrans,
                                        sweepResults);
                                if (sweepResults.hasHit()
                                        && (sweepResults.m_closestHitFraction < (double) 1.f)) {
                                    // printf("clamped integration to hit fraction =
                                    // %f\n",fraction);
                                    body.setHitFraction(sweepResults.m_closestHitFraction);
                                    body.predictIntegratedTransform(
                                            timeStep * body.getHitFraction(), predictedTrans);
                                    body.setHitFraction((double) 0.f);
                                    body.proceedToTransform(predictedTrans);

                                    continue;
                                }
                            }
                        }
                    }

                    body.proceedToTransform(predictedTrans);
                }
            }

            /// this should probably be switched on by default, but it is not well tested yet
            if (m_applySpeculativeContactRestitution) {
                try (CProfileSample __profile2 =
                        new CProfileSample("apply speculative contact restitution")) {
                    for (int i = 0; i < m_predictiveManifolds.size(); i++) {
                        btPersistentManifold manifold = m_predictiveManifolds.get(i);
                        btRigidBody body0 = btRigidBody.upcast(manifold.getBody0());
                        btRigidBody body1 = btRigidBody.upcast(manifold.getBody1());

                        for (int p = 0; p < manifold.getNumContacts(); p++) {
                            btManifoldPoint pt = manifold.getContactPoint(p);
                            double combinedRestitution =
                                    btManifoldResult.calculateCombinedRestitution(body0, body1);

                            if (combinedRestitution > 0 && pt.m_appliedImpulse != (double) 0.f) {
                                // if (pt.getDistance()>0 && combinedRestitution>0 &&
                                // pt.m_appliedImpulse != 0.f)
                                btVector3 imp =
                                        pt.m_normalWorldOnB
                                                .negate()
                                                .mul(pt.m_appliedImpulse)
                                                .mul(combinedRestitution);

                                btVector3 pos1 = pt.getPositionWorldOnA();
                                btVector3 pos2 = pt.getPositionWorldOnB();

                                btVector3 rel_pos0 =
                                        pos1.sub(body0.getWorldTransform().getOrigin());
                                btVector3 rel_pos1 =
                                        pos2.sub(body1.getWorldTransform().getOrigin());

                                if (body0 != null) {
                                    body0.applyImpulse(imp, rel_pos0);
                                }
                                if (body1 != null) {
                                    body1.applyImpulse(imp.negate(), rel_pos1);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void predictUnconstraintMotion(double timeStep) {
        try (CProfileSample __profile = new CProfileSample("predictUnconstraintMotion")) {
            for (int i = 0; i < m_nonStaticRigidBodies.size(); i++) {
                btRigidBody body = m_nonStaticRigidBodies.get(i);
                if (!body.isStaticOrKinematicObject()) {
                    // don't integrate/update velocities here, it happens in the constraint solver

                    body.applyDamping(timeStep);

                    body.predictIntegratedTransform(
                            timeStep, body.getInterpolationWorldTransform());
                }
            }
        }
    }

    public void startProfiling(double timeStep) {
        CProfileManager.Reset();
    }

    /** {@code static int nSegments = 8*4;} (function-local static in debugDrawConstraint) */
    static int nSegments = 8 * 4;

    public void debugDrawConstraint(btTypedConstraint constraint) {
        boolean drawFrames =
                (getDebugDrawer().getDebugMode() & btIDebugDraw.DBG_DrawConstraints) != 0;
        boolean drawLimits =
                (getDebugDrawer().getDebugMode() & btIDebugDraw.DBG_DrawConstraintLimits) != 0;
        double dbgDrawSize = constraint.getDbgDrawSize();
        if (dbgDrawSize <= (double) 0.f) {
            return;
        }

        switch (constraint.getConstraintType()) {
            case btTypedConstraint.POINT2POINT_CONSTRAINT_TYPE:
                {
                    btPoint2PointConstraint p2pC = (btPoint2PointConstraint) constraint;
                    btTransform tr = new btTransform();
                    tr.setIdentity();
                    btVector3 pivot = new btVector3(p2pC.getPivotInA());
                    pivot.set(p2pC.getRigidBodyA().getCenterOfMassTransform().mul(pivot));
                    tr.setOrigin(pivot);
                    getDebugDrawer().drawTransform(tr, dbgDrawSize);
                    // that ideally should draw the same frame
                    pivot.set(p2pC.getPivotInB());
                    pivot.set(p2pC.getRigidBodyB().getCenterOfMassTransform().mul(pivot));
                    tr.setOrigin(pivot);
                    if (drawFrames) {
                        getDebugDrawer().drawTransform(tr, dbgDrawSize);
                    }
                    break;
                }
            case btTypedConstraint.HINGE_CONSTRAINT_TYPE:
                {
                    btHingeConstraint pHinge = (btHingeConstraint) constraint;
                    btTransform tr =
                            pHinge.getRigidBodyA()
                                    .getCenterOfMassTransform()
                                    .mul(pHinge.getAFrame());
                    if (drawFrames) {
                        getDebugDrawer().drawTransform(tr, dbgDrawSize);
                    }
                    tr.set(
                            pHinge.getRigidBodyB()
                                    .getCenterOfMassTransform()
                                    .mul(pHinge.getBFrame()));
                    if (drawFrames) {
                        getDebugDrawer().drawTransform(tr, dbgDrawSize);
                    }
                    double minAng = pHinge.getLowerLimit();
                    double maxAng = pHinge.getUpperLimit();
                    if (minAng == maxAng) {
                        break;
                    }
                    boolean drawSect = true;
                    if (minAng > maxAng) {
                        minAng = (double) 0.f;
                        maxAng = btScalar.SIMD_2_PI;
                        drawSect = false;
                    }
                    if (drawLimits) {
                        btVector3 center = tr.getOrigin();
                        btVector3 normal = tr.getBasis().getColumn(2);
                        btVector3 axis = tr.getBasis().getColumn(0);
                        getDebugDrawer()
                                .drawArc(
                                        center,
                                        normal,
                                        axis,
                                        dbgDrawSize,
                                        dbgDrawSize,
                                        minAng,
                                        maxAng,
                                        new btVector3(0, 0, 0),
                                        drawSect);
                    }
                    break;
                }
            case btTypedConstraint.CONETWIST_CONSTRAINT_TYPE:
                {
                    btConeTwistConstraint pCT = (btConeTwistConstraint) constraint;
                    btTransform tr =
                            pCT.getRigidBodyA().getCenterOfMassTransform().mul(pCT.getAFrame());
                    if (drawFrames) {
                        getDebugDrawer().drawTransform(tr, dbgDrawSize);
                    }
                    tr.set(pCT.getRigidBodyB().getCenterOfMassTransform().mul(pCT.getBFrame()));
                    if (drawFrames) {
                        getDebugDrawer().drawTransform(tr, dbgDrawSize);
                    }
                    if (drawLimits) {
                        // const btScalar length = btScalar(5);
                        final double length = dbgDrawSize;
                        double fAngleInRadians =
                                (2. * 3.1415926) * (double) (nSegments - 1) / (double) nSegments;
                        btVector3 pPrev = pCT.GetPointForAngle(fAngleInRadians, length);
                        pPrev = tr.mul(pPrev);
                        for (int i = 0; i < nSegments; i++) {
                            fAngleInRadians = (2. * 3.1415926) * (double) i / (double) nSegments;
                            btVector3 pCur = pCT.GetPointForAngle(fAngleInRadians, length);
                            pCur = tr.mul(pCur);
                            getDebugDrawer().drawLine(pPrev, pCur, new btVector3(0, 0, 0));

                            if (i % (nSegments / 8) == 0) {
                                getDebugDrawer()
                                        .drawLine(tr.getOrigin(), pCur, new btVector3(0, 0, 0));
                            }

                            pPrev = pCur;
                        }
                        double tws = pCT.getTwistSpan();
                        double twa = pCT.getTwistAngle();
                        boolean useFrameB = (pCT.getRigidBodyB().getInvMass() > (double) 0.f);
                        if (useFrameB) {
                            tr.set(
                                    pCT.getRigidBodyB()
                                            .getCenterOfMassTransform()
                                            .mul(pCT.getBFrame()));
                        } else {
                            tr.set(
                                    pCT.getRigidBodyA()
                                            .getCenterOfMassTransform()
                                            .mul(pCT.getAFrame()));
                        }
                        btVector3 pivot = new btVector3(tr.getOrigin());
                        btVector3 normal = tr.getBasis().getColumn(0);
                        btVector3 axis1 = tr.getBasis().getColumn(1);
                        getDebugDrawer()
                                .drawArc(
                                        pivot,
                                        normal,
                                        axis1,
                                        dbgDrawSize,
                                        dbgDrawSize,
                                        -twa - tws,
                                        -twa + tws,
                                        new btVector3(0, 0, 0),
                                        true);
                    }
                    break;
                }
            case btTypedConstraint.D6_SPRING_CONSTRAINT_TYPE:
            case btTypedConstraint.D6_CONSTRAINT_TYPE:
                {
                    btGeneric6DofConstraint p6DOF = (btGeneric6DofConstraint) constraint;
                    btTransform tr = new btTransform(p6DOF.getCalculatedTransformA());
                    if (drawFrames) {
                        getDebugDrawer().drawTransform(tr, dbgDrawSize);
                    }
                    tr.set(p6DOF.getCalculatedTransformB());
                    if (drawFrames) {
                        getDebugDrawer().drawTransform(tr, dbgDrawSize);
                    }
                    if (drawLimits) {
                        tr.set(p6DOF.getCalculatedTransformA());
                        btVector3 center = p6DOF.getCalculatedTransformB().getOrigin();
                        btVector3 up = tr.getBasis().getColumn(2);
                        btVector3 axis = tr.getBasis().getColumn(0);
                        double minTh = p6DOF.getRotationalLimitMotor(1).m_loLimit;
                        double maxTh = p6DOF.getRotationalLimitMotor(1).m_hiLimit;
                        double minPs = p6DOF.getRotationalLimitMotor(2).m_loLimit;
                        double maxPs = p6DOF.getRotationalLimitMotor(2).m_hiLimit;
                        getDebugDrawer()
                                .drawSpherePatch(
                                        center,
                                        up,
                                        axis,
                                        dbgDrawSize * (double) .9f,
                                        minTh,
                                        maxTh,
                                        minPs,
                                        maxPs,
                                        new btVector3(0, 0, 0));
                        axis.set(tr.getBasis().getColumn(1));
                        double ay = p6DOF.getAngle(1);
                        // GCC fuses btCos(ay)/btSin(ay) into one sincos call (asm @0xb074d /
                        // @0xb0782).
                        double[] scy = new double[2];
                        btScalar.btSinCos(ay, scy);
                        double az = p6DOF.getAngle(2);
                        double[] scz = new double[2];
                        btScalar.btSinCos(az, scz);
                        double cy = scy[1];
                        double sy = scy[0];
                        double cz = scz[1];
                        double sz = scz[0];
                        btVector3 ref = new btVector3();
                        ref.set(
                                0,
                                cy * cz * axis.get(0) + cy * sz * axis.get(1) - sy * axis.get(2));
                        ref.set(1, -sz * axis.get(0) + cz * axis.get(1));
                        ref.set(
                                2,
                                cz * sy * axis.get(0) + sz * sy * axis.get(1) + cy * axis.get(2));
                        tr.set(p6DOF.getCalculatedTransformB());
                        btVector3 normal = tr.getBasis().getColumn(0).negate();
                        double minFi = p6DOF.getRotationalLimitMotor(0).m_loLimit;
                        double maxFi = p6DOF.getRotationalLimitMotor(0).m_hiLimit;
                        if (minFi > maxFi) {
                            getDebugDrawer()
                                    .drawArc(
                                            center,
                                            normal,
                                            ref,
                                            dbgDrawSize,
                                            dbgDrawSize,
                                            -btScalar.SIMD_PI,
                                            btScalar.SIMD_PI,
                                            new btVector3(0, 0, 0),
                                            false);
                        } else if (minFi < maxFi) {
                            getDebugDrawer()
                                    .drawArc(
                                            center,
                                            normal,
                                            ref,
                                            dbgDrawSize,
                                            dbgDrawSize,
                                            minFi,
                                            maxFi,
                                            new btVector3(0, 0, 0),
                                            true);
                        }
                        tr.set(p6DOF.getCalculatedTransformA());
                        btVector3 bbMin =
                                new btVector3(p6DOF.getTranslationalLimitMotor().m_lowerLimit);
                        btVector3 bbMax =
                                new btVector3(p6DOF.getTranslationalLimitMotor().m_upperLimit);
                        getDebugDrawer().drawBox(bbMin, bbMax, tr, new btVector3(0, 0, 0));
                    }
                    break;
                }
            // case SLIDER_CONSTRAINT_TYPE: btSliderConstraint is not linked into the .so (no symbol
            // in
            // INDEX.tsv), so no slider constraint can exist; the case is unreachable and not
            // ported.
            default:
                break;
        }
    }

    @Override
    public void setConstraintSolver(btConstraintSolver solver) {
        if (m_ownsConstraintSolver) {
            btGlobals.btAlignedFree(m_constraintSolverMem);
        }
        m_ownsConstraintSolver = false;
        m_constraintSolver = solver;
        m_solverIslandCallback.m_solver = solver;
    }

    @Override
    public btConstraintSolver getConstraintSolver() {
        return m_constraintSolver;
    }

    @Override
    public int getNumConstraints() {
        return m_constraints.size();
    }

    @Override
    public btTypedConstraint getConstraint(int index) {
        return m_constraints.get(index);
    }

    @Override
    public int getWorldType() {
        return BT_DISCRETE_DYNAMICS_WORLD;
    }

    public void setNumTasks(int numTasks) {}

    /** obsolete, use updateActions instead */
    public void updateVehicles(double timeStep) {
        updateActions(timeStep);
    }

    public void setSynchronizeAllMotionStates(boolean synchronizeAll) {
        m_synchronizeAllMotionStates = synchronizeAll;
    }

    public boolean getSynchronizeAllMotionStates() {
        return m_synchronizeAllMotionStates;
    }

    public void setApplySpeculativeContactRestitution(boolean enable) {
        m_applySpeculativeContactRestitution = enable;
    }

    public boolean getApplySpeculativeContactRestitution() {
        return m_applySpeculativeContactRestitution;
    }

    /**
     * Interpolate motion state between previous and current transform, instead of current and next
     * transform. This can relieve discontinuities in the rendering, due to penetrations
     */
    public void setLatencyMotionStateInterpolation(boolean latencyInterpolation) {
        m_latencyMotionStateInterpolation = latencyInterpolation;
    }

    public boolean getLatencyMotionStateInterpolation() {
        return m_latencyMotionStateInterpolation;
    }
}
