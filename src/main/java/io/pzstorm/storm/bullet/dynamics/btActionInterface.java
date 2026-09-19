// Port of btActionInterface.h (Bullet 2.82) + getFixedBody (btRaycastVehicle.cpp).
package io.pzstorm.storm.bullet.dynamics;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Basic interface to allow actions such as vehicles and characters to be updated inside a
 * btDynamicsWorld.
 */
public abstract class btActionInterface {

    /** function-local {@code static btRigidBody s_fixed(0, 0, 0);} */
    private static btRigidBody s_fixed;

    /**
     * btRaycastVehicle.cpp:
     *
     * <pre>
     * btRigidBody& btActionInterface::getFixedBody()
     * {
     *     static btRigidBody s_fixed(0, 0,0);
     *     s_fixed.setMassProps(btScalar(0.),btVector3(btScalar(0.),btScalar(0.),btScalar(0.)));
     *     return s_fixed;
     * }
     * </pre>
     *
     * The static is a function-local object (not heap-allocated): no btAlignedAlloc, no address
     * consumed from the emulated heap.
     */
    protected static btRigidBody getFixedBody() {
        if (s_fixed == null) {
            s_fixed = new btRigidBody(0.0, null, null, new btVector3(0.0, 0.0, 0.0));
        }
        s_fixed.setMassProps(0.0, new btVector3(0.0, 0.0, 0.0));
        return s_fixed;
    }

    public abstract void updateAction(btCollisionWorld collisionWorld, double deltaTimeStep);

    public abstract void debugDraw(btIDebugDraw debugDrawer);
}
