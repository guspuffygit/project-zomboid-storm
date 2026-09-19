// Port of btSphereShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btSphereShape extends btConvexInternalShape {

    public btSphereShape(double radius) {
        super();
        m_shapeType = BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE;
        m_implicitShapeDimensions.setX(radius);
        m_collisionMargin = radius;
    }

    @Override
    public btVector3 localGetSupportingVertex(btVector3 vec) {
        btVector3 supVertex = new btVector3();
        supVertex.set(localGetSupportingVertexWithoutMargin(vec));

        btVector3 vecnorm = new btVector3(vec);
        if (vecnorm.length2() < (btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON)) {
            vecnorm.setValue(-1.0, -1.0, -1.0);
        }
        vecnorm.normalize();
        supVertex.addLocal(vecnorm.mul(getMargin()));
        return supVertex;
    }

    @Override
    public btVector3 localGetSupportingVertexWithoutMargin(btVector3 vec) {
        return new btVector3(0.0, 0.0, 0.0);
    }

    @Override
    public void batchedUnitVectorGetSupportingVertexWithoutMargin(
            btVector3[] vectors, btVector3[] supportVerticesOut, int numVectors) {
        for (int i = 0; i < numVectors; i++) {
            supportVerticesOut[i].setValue(0.0, 0.0, 0.0);
        }
    }

    @Override
    public void calculateLocalInertia(double mass, btVector3 inertia) {
        double elem = 0.4 * mass * getMargin() * getMargin();
        inertia.setValue(elem, elem, elem);
    }

    @Override
    public void getAabb(btTransform t, btVector3 aabbMin, btVector3 aabbMax) {
        btVector3 center = t.getOrigin();
        btVector3 extent = new btVector3(getMargin(), getMargin(), getMargin());
        aabbMin.set(center.sub(extent));
        aabbMax.set(center.add(extent));
    }

    public double getRadius() {
        return m_implicitShapeDimensions.getX() * m_localScaling.getX();
    }

    public void setUnscaledRadius(double radius) {
        m_implicitShapeDimensions.setX(radius);
        super.setMargin(radius);
    }

    @Override
    public String getName() {
        return "SPHERE";
    }

    @Override
    public void setMargin(double margin) {
        super.setMargin(margin);
    }

    @Override
    public double getMargin() {
        return getRadius();
    }
}
