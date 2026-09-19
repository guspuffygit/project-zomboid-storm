// Port of btRaycastVehicle.h / btRaycastVehicle.cpp (Bullet 2.82) — class btDefaultVehicleRaycaster
package io.pzstorm.storm.bullet.dynamics.vehicle;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.dynamics.btDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btDefaultVehicleRaycaster extends btVehicleRaycaster {
    public btDynamicsWorld m_dynamicsWorld;

    public btDefaultVehicleRaycaster(btDynamicsWorld world) {
        m_dynamicsWorld = world;
    }

    @Override
    public Object castRay(btVector3 from, btVector3 to, btVehicleRaycasterResult result) {
        //	RayResultCallback& resultCallback;

        btCollisionWorld.ClosestRayResultCallback rayCallback =
                new btCollisionWorld.ClosestRayResultCallback(from, to);

        m_dynamicsWorld.rayTest(from, to, rayCallback);

        if (rayCallback.hasHit()) {
            btRigidBody body = btRigidBody.upcast(rayCallback.m_collisionObject);
            if (body != null && body.hasContactResponse()) {
                result.m_hitPointInWorld.set(rayCallback.m_hitPointWorld);
                result.m_hitNormalInWorld.set(rayCallback.m_hitNormalWorld);
                result.m_hitNormalInWorld.normalize();
                result.m_distFraction = rayCallback.m_closestHitFraction;
                return body;
            }
        }
        return null;
    }
}
