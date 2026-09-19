// Port of btStaticPlaneShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Infinite plane. Not constructed by PZ and none of its out-of-line functions are linked into
 * libPZBullet (only referenced as a type, e.g. by btContinuousConvexCollision); ported from
 * upstream for completeness. serialize / calculateSerializeBufferSize are not ported.
 */
public class btStaticPlaneShape extends btConcaveShape {
    public final btVector3 m_localAabbMin = new btVector3();
    public final btVector3 m_localAabbMax = new btVector3();

    public final btVector3 m_planeNormal = new btVector3();
    public double m_planeConstant;
    public final btVector3 m_localScaling = new btVector3(0.0, 0.0, 0.0);

    public btStaticPlaneShape(btVector3 planeNormal, double planeConstant) {
        super();
        m_planeNormal.set(planeNormal.normalized());
        m_planeConstant = planeConstant;
        m_shapeType = BroadphaseNativeTypes.STATIC_PLANE_PROXYTYPE;
    }

    @Override
    public void getAabb(btTransform t, btVector3 aabbMin, btVector3 aabbMax) {
        aabbMin.setValue(
                -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT);
        aabbMax.setValue(btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);
    }

    @Override
    public void processAllTriangles(
            btTriangleCallback callback, btVector3 aabbMin, btVector3 aabbMax) {
        btVector3 halfExtents = aabbMax.sub(aabbMin).mul(0.5);
        double radius = halfExtents.length();
        btVector3 center = aabbMax.add(aabbMin).mul(0.5);

        // this is where the triangles are generated, given AABB and plane equation
        // (normal/constant)

        btVector3 tangentDir0 = new btVector3();
        btVector3 tangentDir1 = new btVector3();

        // tangentDir0/tangentDir1 can be precalculated
        btVector3.btPlaneSpace1(m_planeNormal, tangentDir0, tangentDir1);

        btVector3 projectedCenter =
                center.sub(m_planeNormal.mul(m_planeNormal.dot(center) - m_planeConstant));

        btVector3[] triangle = {new btVector3(), new btVector3(), new btVector3()};
        triangle[0].set(projectedCenter.add(tangentDir0.mul(radius)).add(tangentDir1.mul(radius)));
        triangle[1].set(projectedCenter.add(tangentDir0.mul(radius)).sub(tangentDir1.mul(radius)));
        triangle[2].set(projectedCenter.sub(tangentDir0.mul(radius)).sub(tangentDir1.mul(radius)));

        callback.processTriangle(triangle, 0, 0);

        triangle[0].set(projectedCenter.sub(tangentDir0.mul(radius)).sub(tangentDir1.mul(radius)));
        triangle[1].set(projectedCenter.sub(tangentDir0.mul(radius)).add(tangentDir1.mul(radius)));
        triangle[2].set(projectedCenter.add(tangentDir0.mul(radius)).add(tangentDir1.mul(radius)));

        callback.processTriangle(triangle, 0, 1);
    }

    @Override
    public void calculateLocalInertia(double mass, btVector3 inertia) {
        // moving concave objects not supported
        inertia.setValue(0.0, 0.0, 0.0);
    }

    @Override
    public void setLocalScaling(btVector3 scaling) {
        m_localScaling.set(scaling);
    }

    @Override
    public btVector3 getLocalScaling() {
        return m_localScaling;
    }

    public btVector3 getPlaneNormal() {
        return m_planeNormal;
    }

    public double getPlaneConstant() {
        return m_planeConstant;
    }

    @Override
    public String getName() {
        return "STATICPLANE";
    }
}
