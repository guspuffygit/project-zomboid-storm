// Port of btConvexShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btConvexShape extends btCollisionShape {
    /** {@code #define MAX_PREFERRED_PENETRATION_DIRECTIONS 10} */
    public static final int MAX_PREFERRED_PENETRATION_DIRECTIONS = 10;

    public btConvexShape() {}

    public abstract btVector3 localGetSupportingVertex(btVector3 vec);

    public abstract btVector3 localGetSupportingVertexWithoutMargin(btVector3 vec);

    /** {@code project(trans, dir, btScalar& min, btScalar& max)}; outputs in min[0]/max[0]. */
    public void project(btTransform trans, btVector3 dir, double[] min, double[] max) {
        btVector3 localAxis = btMatrix3x3.mul(dir, trans.getBasis());
        btVector3 vtx1 = trans.transform(localGetSupportingVertex(localAxis));
        btVector3 vtx2 = trans.transform(localGetSupportingVertex(localAxis.negate()));

        min[0] = vtx1.dot(dir);
        max[0] = vtx2.dot(dir);

        if (min[0] > max[0]) {
            double tmp = min[0];
            min[0] = max[0];
            max[0] = tmp;
        }
    }

    /** static convexHullSupport (btConvexShape.cpp), non-SPU path. */
    static btVector3 convexHullSupport(
            btVector3 localDirOrg,
            btAlignedObjectArray<btVector3> points,
            int numPoints,
            btVector3 localScaling) {
        btVector3 vec = localDirOrg.mul(localScaling);
        double[] maxDot = new double[1];
        int ptIndex = btConvexHullShape.maxDot(vec, points, numPoints, maxDot);
        btVector3 supVec = points.get(ptIndex).mul(localScaling);
        return supVec;
    }

    public btVector3 localGetSupportVertexWithoutMarginNonVirtual(btVector3 localDir) {
        switch (m_shapeType) {
            case BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE:
                {
                    return new btVector3(0, 0, 0);
                }
            case BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE:
                {
                    btBoxShape convexShape = (btBoxShape) this;
                    btVector3 halfExtents = convexShape.getImplicitShapeDimensions();
                    return new btVector3(
                            btScalar.btFsels(localDir.x(), halfExtents.x(), -halfExtents.x()),
                            btScalar.btFsels(localDir.y(), halfExtents.y(), -halfExtents.y()),
                            btScalar.btFsels(localDir.z(), halfExtents.z(), -halfExtents.z()));
                }
            case BroadphaseNativeTypes.TRIANGLE_SHAPE_PROXYTYPE:
                {
                    btTriangleShape triangleShape = (btTriangleShape) this;
                    btVector3 dir =
                            new btVector3(localDir.getX(), localDir.getY(), localDir.getZ());
                    btVector3[] vertices = triangleShape.m_vertices1;
                    btVector3 dots = dir.dot3(vertices[0], vertices[1], vertices[2]);
                    btVector3 sup = new btVector3(vertices[dots.maxAxis()]);
                    return new btVector3(sup.getX(), sup.getY(), sup.getZ());
                }
            case BroadphaseNativeTypes.CYLINDER_SHAPE_PROXYTYPE:
                // btCylinderShape is not linked into PZBullet; no instance can have this type.
                throw new IllegalStateException("unreachable: btCylinderShape not linked");
            case BroadphaseNativeTypes.CAPSULE_SHAPE_PROXYTYPE:
                {
                    btVector3 vec0 =
                            new btVector3(localDir.getX(), localDir.getY(), localDir.getZ());

                    btCapsuleShape capsuleShape = (btCapsuleShape) this;
                    double halfHeight = capsuleShape.getHalfHeight();
                    int capsuleUpAxis = capsuleShape.getUpAxis();

                    double radius = capsuleShape.getRadius();
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
                    {
                        btVector3 pos = new btVector3(0, 0, 0);
                        pos.set(capsuleUpAxis, halfHeight);
                        vtx.set(pos.add(vec.mul(radius)).sub(vec.mul(capsuleShape.getMarginNV())));
                        newDot = vec.dot(vtx);

                        if (newDot > maxDot) {
                            maxDot = newDot;
                            supVec.set(vtx);
                        }
                    }
                    {
                        btVector3 pos = new btVector3(0, 0, 0);
                        pos.set(capsuleUpAxis, -halfHeight);
                        vtx.set(pos.add(vec.mul(radius)).sub(vec.mul(capsuleShape.getMarginNV())));
                        newDot = vec.dot(vtx);
                        if (newDot > maxDot) {
                            maxDot = newDot;
                            supVec.set(vtx);
                        }
                    }
                    return new btVector3(supVec.getX(), supVec.getY(), supVec.getZ());
                }
            case BroadphaseNativeTypes.CONVEX_POINT_CLOUD_SHAPE_PROXYTYPE:
                // btConvexPointCloudShape is not linked into PZBullet.
                throw new IllegalStateException("unreachable: btConvexPointCloudShape not linked");
            case BroadphaseNativeTypes.CONVEX_HULL_SHAPE_PROXYTYPE:
                {
                    btConvexHullShape convexHullShape = (btConvexHullShape) this;
                    btAlignedObjectArray<btVector3> points = convexHullShape.m_unscaledPoints;
                    int numPoints = convexHullShape.getNumPoints();
                    return convexHullSupport(
                            localDir, points, numPoints, convexHullShape.getLocalScalingNV());
                }
            default:
                return this.localGetSupportingVertexWithoutMargin(localDir);
        }
    }

    public btVector3 localGetSupportVertexNonVirtual(btVector3 localDir) {
        btVector3 localDirNorm = new btVector3(localDir);
        if (localDirNorm.length2() < (btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON)) {
            localDirNorm.setValue(-1.0, -1.0, -1.0);
        }
        localDirNorm.normalize();

        return localGetSupportVertexWithoutMarginNonVirtual(localDirNorm)
                .add(localDirNorm.mul(getMarginNonVirtual()));
    }

    public double getMarginNonVirtual() {
        switch (m_shapeType) {
            case BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE:
                {
                    btSphereShape sphereShape = (btSphereShape) this;
                    return sphereShape.getRadius();
                }
            case BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE:
            case BroadphaseNativeTypes.TRIANGLE_SHAPE_PROXYTYPE:
            case BroadphaseNativeTypes.CYLINDER_SHAPE_PROXYTYPE:
            case BroadphaseNativeTypes.CONE_SHAPE_PROXYTYPE:
            case BroadphaseNativeTypes.CAPSULE_SHAPE_PROXYTYPE:
            case BroadphaseNativeTypes.CONVEX_POINT_CLOUD_SHAPE_PROXYTYPE:
            case BroadphaseNativeTypes.CONVEX_HULL_SHAPE_PROXYTYPE:
                {
                    // every case is a (non-virtual) getMarginNV() == m_collisionMargin
                    btConvexInternalShape s = (btConvexInternalShape) this;
                    return s.getMarginNV();
                }
            default:
                return this.getMargin();
        }
    }

    public void getAabbNonVirtual(btTransform t, btVector3 aabbMin, btVector3 aabbMax) {
        switch (m_shapeType) {
            case BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE:
                {
                    btSphereShape sphereShape = (btSphereShape) this;
                    double radius = sphereShape.getImplicitShapeDimensions().getX();
                    double margin = radius + sphereShape.getMarginNonVirtual();
                    btVector3 center = t.getOrigin();
                    btVector3 extent = new btVector3(margin, margin, margin);
                    aabbMin.set(center.sub(extent));
                    aabbMax.set(center.add(extent));
                }
                break;
            case BroadphaseNativeTypes.CYLINDER_SHAPE_PROXYTYPE:
            case BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE:
                {
                    // C++ casts to btBoxShape for both cases; only the btConvexInternalShape
                    // members are used.
                    btConvexInternalShape convexShape = (btConvexInternalShape) this;
                    double margin = convexShape.getMarginNonVirtual();
                    btVector3 halfExtents = new btVector3(convexShape.getImplicitShapeDimensions());
                    halfExtents.addLocal(new btVector3(margin, margin, margin));
                    btMatrix3x3 abs_b = t.getBasis().absolute();
                    btVector3 center = new btVector3(t.getOrigin());
                    btVector3 extent = halfExtents.dot3(abs_b.get(0), abs_b.get(1), abs_b.get(2));

                    aabbMin.set(center.sub(extent));
                    aabbMax.set(center.add(extent));
                    break;
                }
            case BroadphaseNativeTypes.TRIANGLE_SHAPE_PROXYTYPE:
                {
                    btTriangleShape triangleShape = (btTriangleShape) this;
                    double margin = triangleShape.getMarginNonVirtual();
                    for (int i = 0; i < 3; i++) {
                        btVector3 vec = new btVector3(0.0, 0.0, 0.0);
                        vec.set(i, 1.0);

                        btVector3 sv =
                                localGetSupportVertexWithoutMarginNonVirtual(
                                        btMatrix3x3.mul(vec, t.getBasis()));

                        btVector3 tmp = t.transform(sv);
                        aabbMax.set(i, tmp.get(i) + margin);
                        vec.set(i, -1.0);
                        tmp =
                                t.transform(
                                        localGetSupportVertexWithoutMarginNonVirtual(
                                                btMatrix3x3.mul(vec, t.getBasis())));
                        aabbMin.set(i, tmp.get(i) - margin);
                    }
                }
                break;
            case BroadphaseNativeTypes.CAPSULE_SHAPE_PROXYTYPE:
                {
                    btCapsuleShape capsuleShape = (btCapsuleShape) this;
                    btVector3 halfExtents =
                            new btVector3(
                                    capsuleShape.getRadius(),
                                    capsuleShape.getRadius(),
                                    capsuleShape.getRadius());
                    int m_upAxis = capsuleShape.getUpAxis();
                    halfExtents.set(
                            m_upAxis, capsuleShape.getRadius() + capsuleShape.getHalfHeight());
                    halfExtents.addLocal(
                            new btVector3(
                                    capsuleShape.getMarginNonVirtual(),
                                    capsuleShape.getMarginNonVirtual(),
                                    capsuleShape.getMarginNonVirtual()));
                    btMatrix3x3 abs_b = t.getBasis().absolute();
                    btVector3 center = new btVector3(t.getOrigin());
                    btVector3 extent = halfExtents.dot3(abs_b.get(0), abs_b.get(1), abs_b.get(2));

                    aabbMin.set(center.sub(extent));
                    aabbMax.set(center.add(extent));
                }
                break;
            case BroadphaseNativeTypes.CONVEX_POINT_CLOUD_SHAPE_PROXYTYPE:
            case BroadphaseNativeTypes.CONVEX_HULL_SHAPE_PROXYTYPE:
                {
                    btPolyhedralConvexAabbCachingShape convexHullShape =
                            (btPolyhedralConvexAabbCachingShape) this;
                    double margin = convexHullShape.getMarginNonVirtual();
                    convexHullShape.getNonvirtualAabb(t, aabbMin, aabbMax, margin);
                }
                break;
            default:
                this.getAabb(t, aabbMin, aabbMax);
                break;
        }
    }

    /**
     * {@code batchedUnitVectorGetSupportingVertexWithoutMargin(const btVector3* vectors, btVector3*
     * supportVerticesOut, int numVectors)}.
     */
    public abstract void batchedUnitVectorGetSupportingVertexWithoutMargin(
            btVector3[] vectors, btVector3[] supportVerticesOut, int numVectors);

    @Override
    public abstract void getAabb(btTransform t, btVector3 aabbMin, btVector3 aabbMax);

    public abstract void getAabbSlow(btTransform t, btVector3 aabbMin, btVector3 aabbMax);

    @Override
    public abstract void setLocalScaling(btVector3 scaling);

    @Override
    public abstract btVector3 getLocalScaling();

    @Override
    public abstract void setMargin(double margin);

    @Override
    public abstract double getMargin();

    public abstract int getNumPreferredPenetrationDirections();

    public abstract void getPreferredPenetrationDirection(int index, btVector3 penetrationVector);
}
