// Port of PZ glue PZDiscreteDynamicsWorld (btDiscreteDynamicsWorld subclass; overrides
// saveKinematicState so static (kinematic) vehicle chassis get their kinematic velocity).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseInterface;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionConfiguration;
import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btConstraintSolver;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class PZDiscreteDynamicsWorld extends btDiscreteDynamicsWorld {

    public PZDiscreteDynamicsWorld(
            btDispatcher dispatcher,
            btBroadphaseInterface pairCache,
            btConstraintSolver constraintSolver,
            btCollisionConfiguration collisionConfiguration) {
        super(dispatcher, pairCache, constraintSolver, collisionConfiguration);
    }

    /**
     * @00187180 (vtable slot +0x168): tail-calls m_debugDrawer-&gt;drawLine(from, to, color) with
     * no null check (a null drawer is a C++ null dereference; Java NPE).
     */
    public void debugDrawLine(btVector3 from, btVector3 to, btVector3 color) {
        getDebugDrawer().drawLine(from, to, color);
    }

    // debugDrawObject @00187190 only forwards to btCollisionWorld::debugDrawObject, so the
    // inherited Java method is the port; no override is needed.

    /** Only static vehicles' chassis are kinematic-saved (iterates WorldSimulation::m_vehicles). */
    @Override
    public void saveKinematicState(double timeStep) {
        for (PZVehicle v : WorldSimulation.instance.m_vehicles.values()) {
            if (v.m_isStatic) {
                v.m_carChassis.saveKinematicState(timeStep);
            }
        }
    }
}
