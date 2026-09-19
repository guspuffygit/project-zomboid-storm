// Port of btBoxShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import io.pzstorm.storm.bullet.linearmath.btVector4;

public class btBoxShape extends btPolyhedralConvexShape {

    public btVector3 getHalfExtentsWithMargin() {
        btVector3 halfExtents = new btVector3(getHalfExtentsWithoutMargin());
        btVector3 margin = new btVector3(getMargin(), getMargin(), getMargin());
        halfExtents.addLocal(margin);
        return halfExtents;
    }

    public btVector3 getHalfExtentsWithoutMargin() {
        return m_implicitShapeDimensions; // scaling is included, margin is not
    }

    @Override
    public btVector3 localGetSupportingVertex(btVector3 vec) {
        btVector3 halfExtents = new btVector3(getHalfExtentsWithoutMargin());
        btVector3 margin = new btVector3(getMargin(), getMargin(), getMargin());
        halfExtents.addLocal(margin);

        return new btVector3(
                btScalar.btFsels(vec.x(), halfExtents.x(), -halfExtents.x()),
                btScalar.btFsels(vec.y(), halfExtents.y(), -halfExtents.y()),
                btScalar.btFsels(vec.z(), halfExtents.z(), -halfExtents.z()));
    }

    @Override
    public btVector3 localGetSupportingVertexWithoutMargin(btVector3 vec) {
        btVector3 halfExtents = getHalfExtentsWithoutMargin();

        return new btVector3(
                btScalar.btFsels(vec.x(), halfExtents.x(), -halfExtents.x()),
                btScalar.btFsels(vec.y(), halfExtents.y(), -halfExtents.y()),
                btScalar.btFsels(vec.z(), halfExtents.z(), -halfExtents.z()));
    }

    @Override
    public void batchedUnitVectorGetSupportingVertexWithoutMargin(
            btVector3[] vectors, btVector3[] supportVerticesOut, int numVectors) {
        btVector3 halfExtents = getHalfExtentsWithoutMargin();

        for (int i = 0; i < numVectors; i++) {
            btVector3 vec = vectors[i];
            supportVerticesOut[i].setValue(
                    btScalar.btFsels(vec.x(), halfExtents.x(), -halfExtents.x()),
                    btScalar.btFsels(vec.y(), halfExtents.y(), -halfExtents.y()),
                    btScalar.btFsels(vec.z(), halfExtents.z(), -halfExtents.z()));
        }
    }

    public btBoxShape(btVector3 boxHalfExtents) {
        super();
        m_shapeType = BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE;

        setSafeMargin(boxHalfExtents);

        btVector3 margin = new btVector3(getMargin(), getMargin(), getMargin());
        m_implicitShapeDimensions.set((boxHalfExtents.mul(m_localScaling)).sub(margin));
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
    public void getAabb(btTransform t, btVector3 aabbMin, btVector3 aabbMax) {
        btAabbUtil2.btTransformAabb(
                getHalfExtentsWithoutMargin(), getMargin(), t, aabbMin, aabbMax);
    }

    @Override
    public void calculateLocalInertia(double mass, btVector3 inertia) {
        btVector3 halfExtents = getHalfExtentsWithMargin();

        double lx = 2.0 * (halfExtents.x());
        double ly = 2.0 * (halfExtents.y());
        double lz = 2.0 * (halfExtents.z());

        inertia.setValue(
                mass / (12.0) * (ly * ly + lz * lz),
                mass / (12.0) * (lx * lx + lz * lz),
                mass / (12.0) * (lx * lx + ly * ly));
    }

    @Override
    public void getPlane(btVector3 planeNormal, btVector3 planeSupport, int i) {
        // this plane might not be aligned...
        btVector4 plane = new btVector4();
        getPlaneEquation(plane, i);
        planeNormal.set(new btVector3(plane.getX(), plane.getY(), plane.getZ()));
        planeSupport.set(localGetSupportingVertex(planeNormal.negate()));
    }

    @Override
    public int getNumPlanes() {
        return 6;
    }

    @Override
    public int getNumVertices() {
        return 8;
    }

    @Override
    public int getNumEdges() {
        return 12;
    }

    @Override
    public void getVertex(int i, btVector3 vtx) {
        btVector3 halfExtents = getHalfExtentsWithMargin();

        vtx.set(
                new btVector3(
                        halfExtents.x() * (1 - (i & 1)) - halfExtents.x() * (i & 1),
                        halfExtents.y() * (1 - ((i & 2) >> 1)) - halfExtents.y() * ((i & 2) >> 1),
                        halfExtents.z() * (1 - ((i & 4) >> 2)) - halfExtents.z() * ((i & 4) >> 2)));
    }

    public void getPlaneEquation(btVector4 plane, int i) {
        btVector3 halfExtents = new btVector3(getHalfExtentsWithoutMargin());

        switch (i) {
            case 0:
                plane.setValue(1., 0., 0., -halfExtents.x());
                break;
            case 1:
                plane.setValue(-1., 0., 0., -halfExtents.x());
                break;
            case 2:
                plane.setValue(0., 1., 0., -halfExtents.y());
                break;
            case 3:
                plane.setValue(0., -1., 0., -halfExtents.y());
                break;
            case 4:
                plane.setValue(0., 0., 1., -halfExtents.z());
                break;
            case 5:
                plane.setValue(0., 0., -1., -halfExtents.z());
                break;
            default:
        }
    }

    @Override
    public void getEdge(int i, btVector3 pa, btVector3 pb) {
        int edgeVert0 = 0;
        int edgeVert1 = 0;

        switch (i) {
            case 0:
                edgeVert0 = 0;
                edgeVert1 = 1;
                break;
            case 1:
                edgeVert0 = 0;
                edgeVert1 = 2;
                break;
            case 2:
                edgeVert0 = 1;
                edgeVert1 = 3;
                break;
            case 3:
                edgeVert0 = 2;
                edgeVert1 = 3;
                break;
            case 4:
                edgeVert0 = 0;
                edgeVert1 = 4;
                break;
            case 5:
                edgeVert0 = 1;
                edgeVert1 = 5;
                break;
            case 6:
                edgeVert0 = 2;
                edgeVert1 = 6;
                break;
            case 7:
                edgeVert0 = 3;
                edgeVert1 = 7;
                break;
            case 8:
                edgeVert0 = 4;
                edgeVert1 = 5;
                break;
            case 9:
                edgeVert0 = 4;
                edgeVert1 = 6;
                break;
            case 10:
                edgeVert0 = 5;
                edgeVert1 = 7;
                break;
            case 11:
                edgeVert0 = 6;
                edgeVert1 = 7;
                break;
            default:
        }

        getVertex(edgeVert0, pa);
        getVertex(edgeVert1, pb);
    }

    @Override
    public boolean isInside(btVector3 pt, double tolerance) {
        btVector3 halfExtents = new btVector3(getHalfExtentsWithoutMargin());

        boolean result =
                (pt.x() <= (halfExtents.x() + tolerance))
                        && (pt.x() >= (-halfExtents.x() - tolerance))
                        && (pt.y() <= (halfExtents.y() + tolerance))
                        && (pt.y() >= (-halfExtents.y() - tolerance))
                        && (pt.z() <= (halfExtents.z() + tolerance))
                        && (pt.z() >= (-halfExtents.z() - tolerance));

        return result;
    }

    @Override
    public String getName() {
        return "Box";
    }

    @Override
    public int getNumPreferredPenetrationDirections() {
        return 6;
    }

    @Override
    public void getPreferredPenetrationDirection(int index, btVector3 penetrationVector) {
        switch (index) {
            case 0:
                penetrationVector.setValue(1., 0., 0.);
                break;
            case 1:
                penetrationVector.setValue(-1., 0., 0.);
                break;
            case 2:
                penetrationVector.setValue(0., 1., 0.);
                break;
            case 3:
                penetrationVector.setValue(0., -1., 0.);
                break;
            case 4:
                penetrationVector.setValue(0., 0., 1.);
                break;
            case 5:
                penetrationVector.setValue(0., 0., -1.);
                break;
            default:
        }
    }
}
