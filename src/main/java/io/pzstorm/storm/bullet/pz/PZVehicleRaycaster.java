// Port of PZ PZVehicleRaycaster (PZVehicleRaycast.cpp) from decomp/asm:
//   castRay @0015d950 (asm 0x5d950), dtors @0015dc00/@0015dc30.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.vehicle.btVehicleRaycaster;
import io.pzstorm.storm.bullet.libm.GlibcMath;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** btVehicleRaycaster that ignores non-rigid-body hits and hits on static-flagged objects. */
public class PZVehicleRaycaster extends btVehicleRaycaster {

    /** +8 */
    public btDiscreteDynamicsWorld m_dynamicsWorld;

    public PZVehicleRaycaster(btDiscreteDynamicsWorld world) {
        m_dynamicsWorld = world;
    }

    /** virtual void* castRay(const btVector3&, const btVector3&, btVehicleRaycasterResult&) */
    @Override
    public Object castRay(btVector3 from, btVector3 to, btVehicleRaycasterResult result) {
        btCollisionWorld.ClosestRayResultCallback cb =
                new btCollisionWorld.ClosestRayResultCallback(from, to);
        cb.m_collisionFilterGroup = (short) 2;
        cb.m_collisionFilterMask = (short) 0xd;
        cb.m_flags = 0;
        m_dynamicsWorld.rayTest(from, to, cb);
        btCollisionObject o = cb.m_collisionObject;
        if (o == null) {
            return null;
        }
        // btRigidBody::upcast (internalType & CO_RIGID_BODY) and !(flags & CF_NO_CONTACT_RESPONSE)
        if ((o.getInternalType() & 2) == 0 || (o.getCollisionFlags() & 4) != 0) {
            return null;
        }
        result.m_hitPointInWorld.set(cb.m_hitPointWorld);
        result.m_hitNormalInWorld.set(cb.m_hitNormalWorld);
        btVector3 n = result.m_hitNormalInWorld;
        double len2 = n.x * n.x + n.y * n.y + n.z * n.z;
        double inv = 1.0 / GlibcMath.sqrt(len2);
        n.x = n.x * inv;
        n.y = n.y * inv;
        n.z = n.z * inv;
        result.m_distFraction = cb.m_closestHitFraction;
        return o;
    }
}
