// Port of btCapsuleShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btCapsuleShape extends btConvexInternalShape {
    public int m_upAxis;

    /** only used for btCapsuleShapeZ and btCapsuleShapeX subclasses. */
    protected btCapsuleShape() {
        super();
        m_shapeType = BroadphaseNativeTypes.CAPSULE_SHAPE_PROXYTYPE;
    }

    public btCapsuleShape(double radius, double height) {
        super();
        m_shapeType = BroadphaseNativeTypes.CAPSULE_SHAPE_PROXYTYPE;
        m_upAxis = 1;
        m_implicitShapeDimensions.setValue(radius, 0.5 * height, radius);
    }

    @Override
    public btVector3 localGetSupportingVertexWithoutMargin(btVector3 vec0) {
        btVector3 supVec = new btVector3(0, 0, 0);

        double maxDot = -btScalar.BT_LARGE_FLOAT;

        btVector3 vec = new btVector3(vec0);
        double lenSqr = vec.length2();
        if (lenSqr < 0.0001) {
            vec.setValue(1, 0, 0);
        } else {
            double rlen = 1.0 / btScalar.btSqrt(lenSqr);
            vec.mulLocal(rlen);
        }

        btVector3 vtx = new btVector3();
        double newDot;

        double radius = getRadius();

        {
            btVector3 pos = new btVector3(0, 0, 0);
            pos.set(getUpAxis(), getHalfHeight());

            vtx.set(pos.add(vec.mul(radius)).sub(vec.mul(getMargin())));
            newDot = vec.dot(vtx);
            if (newDot > maxDot) {
                maxDot = newDot;
                supVec.set(vtx);
            }
        }
        {
            btVector3 pos = new btVector3(0, 0, 0);
            pos.set(getUpAxis(), -getHalfHeight());

            vtx.set(pos.add(vec.mul(radius)).sub(vec.mul(getMargin())));
            newDot = vec.dot(vtx);
            if (newDot > maxDot) {
                maxDot = newDot;
                supVec.set(vtx);
            }
        }

        return supVec;
    }

    @Override
    public void batchedUnitVectorGetSupportingVertexWithoutMargin(
            btVector3[] vectors, btVector3[] supportVerticesOut, int numVectors) {
        double radius = getRadius();

        for (int j = 0; j < numVectors; j++) {
            double maxDot = -btScalar.BT_LARGE_FLOAT;
            btVector3 vec = vectors[j];

            btVector3 vtx = new btVector3();
            double newDot;
            {
                btVector3 pos = new btVector3(0, 0, 0);
                pos.set(getUpAxis(), getHalfHeight());
                vtx.set(pos.add(vec.mul(radius)).sub(vec.mul(getMargin())));
                newDot = vec.dot(vtx);
                if (newDot > maxDot) {
                    maxDot = newDot;
                    supportVerticesOut[j].set(vtx);
                }
            }
            {
                btVector3 pos = new btVector3(0, 0, 0);
                pos.set(getUpAxis(), -getHalfHeight());
                vtx.set(pos.add(vec.mul(radius)).sub(vec.mul(getMargin())));
                newDot = vec.dot(vtx);
                if (newDot > maxDot) {
                    maxDot = newDot;
                    supportVerticesOut[j].set(vtx);
                }
            }
        }
    }

    @Override
    public void calculateLocalInertia(double mass, btVector3 inertia) {
        // as an approximation, take the inertia of the box that bounds the spheres

        btTransform ident = new btTransform();
        ident.setIdentity();

        double radius = getRadius();

        btVector3 halfExtents = new btVector3(radius, radius, radius);
        halfExtents.set(getUpAxis(), halfExtents.get(getUpAxis()) + getHalfHeight());

        double margin = btCollisionMargin.CONVEX_DISTANCE_MARGIN;

        double lx = 2.0 * (halfExtents.get(0) + margin);
        double ly = 2.0 * (halfExtents.get(1) + margin);
        double lz = 2.0 * (halfExtents.get(2) + margin);
        double x2 = lx * lx;
        double y2 = ly * ly;
        double z2 = lz * lz;
        double scaledmass = mass * .08333333;

        inertia.set(0, scaledmass * (y2 + z2));
        inertia.set(1, scaledmass * (x2 + z2));
        inertia.set(2, scaledmass * (x2 + y2));
    }

    @Override
    public void setMargin(double collisionMargin) {
        // correct the m_implicitShapeDimensions for the margin
        btVector3 oldMargin = new btVector3(getMargin(), getMargin(), getMargin());
        btVector3 implicitShapeDimensionsWithMargin = m_implicitShapeDimensions.add(oldMargin);

        super.setMargin(collisionMargin);
        btVector3 newMargin = new btVector3(getMargin(), getMargin(), getMargin());
        m_implicitShapeDimensions.set(implicitShapeDimensionsWithMargin.sub(newMargin));
    }

    @Override
    public void getAabb(btTransform t, btVector3 aabbMin, btVector3 aabbMax) {
        btVector3 halfExtents = new btVector3(getRadius(), getRadius(), getRadius());
        halfExtents.set(m_upAxis, getRadius() + getHalfHeight());
        halfExtents.addLocal(new btVector3(getMargin(), getMargin(), getMargin()));
        btMatrix3x3 abs_b = t.getBasis().absolute();
        btVector3 center = new btVector3(t.getOrigin());
        btVector3 extent = halfExtents.dot3(abs_b.get(0), abs_b.get(1), abs_b.get(2));

        aabbMin.set(center.sub(extent));
        aabbMax.set(center.add(extent));
    }

    @Override
    public String getName() {
        return "CapsuleShape";
    }

    public int getUpAxis() {
        return m_upAxis;
    }

    public double getRadius() {
        int radiusAxis = (m_upAxis + 2) % 3;
        return m_implicitShapeDimensions.get(radiusAxis);
    }

    public double getHalfHeight() {
        return m_implicitShapeDimensions.get(m_upAxis);
    }

    @Override
    public void setLocalScaling(btVector3 scaling) {
        btVector3 oldMargin = new btVector3(getMargin(), getMargin(), getMargin());
        btVector3 implicitShapeDimensionsWithMargin = m_implicitShapeDimensions.add(oldMargin);
        btVector3 unScaledImplicitShapeDimensionsWithMargin =
                implicitShapeDimensionsWithMargin.div(m_localScaling);

        super.setLocalScaling(scaling);

        m_implicitShapeDimensions.set(
                (unScaledImplicitShapeDimensionsWithMargin.mul(m_localScaling)).sub(oldMargin));
    }

    @Override
    public btVector3 getAnisotropicRollingFrictionDirection() {
        btVector3 aniDir = new btVector3(0, 0, 0);
        aniDir.set(getUpAxis(), 1);
        return aniDir;
    }
}
