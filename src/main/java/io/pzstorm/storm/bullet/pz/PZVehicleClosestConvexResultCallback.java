// Port of PZ PZVehicleClosestConvexResultCallback (PZBullet.cpp) from decomp:
//   addSingleResult @00158090, dtors @00157ff0/@00158000.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * ClosestConvexResultCallback that ignores hits against its own compound shape (used by
 * setOwnVehiclePhysics sweeps).
 */
public class PZVehicleClosestConvexResultCallback
        extends btCollisionWorld.ClosestConvexResultCallback {

    /** +0xa0: the sweeping vehicle's compound shape. */
    public btCollisionShape m_me;

    public PZVehicleClosestConvexResultCallback(
            btVector3 convexFromWorld, btVector3 convexToWorld) {
        super(convexFromWorld, convexToWorld);
    }

    @Override
    public double addSingleResult(
            btCollisionWorld.LocalConvexResult convexResult, boolean normalInWorldSpace) {
        btCollisionObject obj = convexResult.m_hitCollisionObject;
        btCollisionShape shape = obj.getCollisionShape();
        if (shape.getShapeType() == 31 && m_me == shape) {
            return 1.0;
        }
        m_hitCollisionObject = obj;
        m_closestHitFraction = convexResult.m_hitFraction;
        btVector3 n = convexResult.m_hitNormalLocal;
        if (normalInWorldSpace) {
            m_hitNormalWorld.set(n);
        } else {
            btMatrix3x3 b = obj.getWorldTransform().getBasis();
            double x = n.x * b.getRow(0).x + n.y * b.getRow(0).y + n.z * b.getRow(0).z;
            double y = n.x * b.getRow(1).x + n.y * b.getRow(1).y + n.z * b.getRow(1).z;
            double z = n.x * b.getRow(2).x + n.y * b.getRow(2).y + n.z * b.getRow(2).z;
            m_hitNormalWorld.x = x;
            m_hitNormalWorld.y = y;
            m_hitNormalWorld.z = z;
            m_hitNormalWorld.w = 0.0;
        }
        m_hitPointWorld.set(convexResult.m_hitPointLocal);
        return convexResult.m_hitFraction;
    }
}
