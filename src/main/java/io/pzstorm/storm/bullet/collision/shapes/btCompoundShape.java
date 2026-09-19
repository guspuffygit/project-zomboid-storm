// Port of btCompoundShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvt;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtAabbMm;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The btCompoundShape allows to store multiple other btCollisionShapes. It has an (optional)
 * dynamic aabb tree to accelerate early rejection tests. serialize/calculateSerializeBufferSize are
 * linked but never called and are not ported. The destructor (destroys the owned btDbvt) is {@link
 * #destroy()}.
 */
public class btCompoundShape extends btCollisionShape {
    public final btAlignedObjectArray<btCompoundShapeChild> m_children =
            new btAlignedObjectArray<>(btCompoundShapeChild::new, btCompoundShapeChild::assign);
    public final btVector3 m_localAabbMin =
            new btVector3(
                    btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);
    public final btVector3 m_localAabbMax =
            new btVector3(
                    -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT);
    public btDbvt m_dynamicAabbTree;
    /// increment m_updateRevision when adding/removing/replacing child shapes, so that some caches
    // can be updated
    public int m_updateRevision;
    public double m_collisionMargin;

    public final btVector3 m_localScaling = new btVector3(1.0, 1.0, 1.0);

    public btCompoundShape() {
        this(true);
    }

    public btCompoundShape(boolean enableDynamicAabbTree) {
        super();
        m_dynamicAabbTree = null;
        m_updateRevision = 1;
        m_collisionMargin = 0.0;
        m_shapeType = BroadphaseNativeTypes.COMPOUND_SHAPE_PROXYTYPE;

        if (enableDynamicAabbTree) {
            m_dynamicAabbTree = new btDbvt();
        }
    }

    /** {@code ~btCompoundShape()} */
    public void destroy() {
        if (m_dynamicAabbTree != null) {
            m_dynamicAabbTree.destroy();
            m_dynamicAabbTree = null;
        }
    }

    public void addChildShape(btTransform localTransform, btCollisionShape shape) {
        m_updateRevision++;
        btCompoundShapeChild child = new btCompoundShapeChild();
        child.m_node = null;
        child.m_transform.set(localTransform);
        child.m_childShape = shape;
        child.m_childShapeType = shape.getShapeType();
        child.m_childMargin = shape.getMargin();

        // extend the local aabbMin/aabbMax
        btVector3 localAabbMin = new btVector3(), localAabbMax = new btVector3();
        shape.getAabb(localTransform, localAabbMin, localAabbMax);
        for (int i = 0; i < 3; i++) {
            if (m_localAabbMin.get(i) > localAabbMin.get(i)) {
                m_localAabbMin.set(i, localAabbMin.get(i));
            }
            if (m_localAabbMax.get(i) < localAabbMax.get(i)) {
                m_localAabbMax.set(i, localAabbMax.get(i));
            }
        }
        if (m_dynamicAabbTree != null) {
            btDbvtAabbMm bounds = btDbvtAabbMm.FromMM(localAabbMin, localAabbMax);
            int index = m_children.size();
            child.m_node = m_dynamicAabbTree.insert(bounds, Integer.valueOf(index));
        }

        m_children.push_back(child);
    }

    /** Remove all children shapes that contain the specified shape */
    public void removeChildShape(btCollisionShape shape) {
        m_updateRevision++;
        // Find the children containing the shape specified, and remove those children.
        // note: there might be multiple children using the same shape!
        for (int i = m_children.size() - 1; i >= 0; i--) {
            if (m_children.get(i).m_childShape == shape) {
                removeChildShapeByIndex(i);
            }
        }

        recalculateLocalAabb();
    }

    public void removeChildShapeByIndex(int childShapeIndex) {
        m_updateRevision++;
        if (m_dynamicAabbTree != null) {
            m_dynamicAabbTree.remove(m_children.get(childShapeIndex).m_node);
        }
        m_children.swap(childShapeIndex, m_children.size() - 1);
        if (m_dynamicAabbTree != null)
            m_children.get(childShapeIndex).m_node.dataAsInt = childShapeIndex;
        m_children.pop_back();
    }

    public int getNumChildShapes() {
        return m_children.size();
    }

    public btCollisionShape getChildShape(int index) {
        return m_children.get(index).m_childShape;
    }

    /** returns the child's transform by reference */
    public btTransform getChildTransform(int index) {
        return m_children.get(index).m_transform;
    }

    public void updateChildTransform(int childIndex, btTransform newChildTransform) {
        updateChildTransform(childIndex, newChildTransform, true);
    }

    /// set a new transform for a child, and update internal data structures (local aabb and dynamic
    // tree)
    public void updateChildTransform(
            int childIndex, btTransform newChildTransform, boolean shouldRecalculateLocalAabb) {
        m_children.get(childIndex).m_transform.set(newChildTransform);

        if (m_dynamicAabbTree != null) {
            /// update the dynamic aabb tree
            btVector3 localAabbMin = new btVector3(), localAabbMax = new btVector3();
            m_children
                    .get(childIndex)
                    .m_childShape
                    .getAabb(newChildTransform, localAabbMin, localAabbMax);
            btDbvtAabbMm bounds = btDbvtAabbMm.FromMM(localAabbMin, localAabbMax);
            m_dynamicAabbTree.update(m_children.get(childIndex).m_node, bounds);
        }

        if (shouldRecalculateLocalAabb) {
            recalculateLocalAabb();
        }
    }

    /** {@code getChildList()}: the children array itself (C++ returns {@code &m_children[0]}). */
    public btAlignedObjectArray<btCompoundShapeChild> getChildList() {
        return m_children;
    }

    /// getAabb's default implementation is brute force, expected derived classes to implement a
    // fast dedicated version
    @Override
    public void getAabb(btTransform trans, btVector3 aabbMin, btVector3 aabbMax) {
        btVector3 localHalfExtents = m_localAabbMax.sub(m_localAabbMin).mul(0.5);
        btVector3 localCenter = m_localAabbMax.add(m_localAabbMin).mul(0.5);

        // avoid an illegal AABB when there are no children
        if (m_children.size() == 0) {
            localHalfExtents.setValue(0, 0, 0);
            localCenter.setValue(0, 0, 0);
        }
        localHalfExtents.addLocal(new btVector3(getMargin(), getMargin(), getMargin()));

        btMatrix3x3 abs_b = trans.getBasis().absolute();

        btVector3 center = trans.transform(localCenter);

        btVector3 extent = localHalfExtents.dot3(abs_b.get(0), abs_b.get(1), abs_b.get(2));
        aabbMin.set(center.sub(extent));
        aabbMax.set(center.add(extent));
    }

    /**
     * Re-calculate the local Aabb. Is called at the end of removeChildShapes. Use this yourself if
     * you modify the children or their transforms.
     */
    public void recalculateLocalAabb() {
        // Recalculate the local aabb
        // Brute force, it iterates over all the shapes left.

        m_localAabbMin.set(
                new btVector3(
                        btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT));
        m_localAabbMax.set(
                new btVector3(
                        -btScalar.BT_LARGE_FLOAT,
                        -btScalar.BT_LARGE_FLOAT,
                        -btScalar.BT_LARGE_FLOAT));

        // extend the local aabbMin/aabbMax
        for (int j = 0; j < m_children.size(); j++) {
            btVector3 localAabbMin = new btVector3(), localAabbMax = new btVector3();
            m_children
                    .get(j)
                    .m_childShape
                    .getAabb(m_children.get(j).m_transform, localAabbMin, localAabbMax);
            for (int i = 0; i < 3; i++) {
                if (m_localAabbMin.get(i) > localAabbMin.get(i))
                    m_localAabbMin.set(i, localAabbMin.get(i));
                if (m_localAabbMax.get(i) < localAabbMax.get(i))
                    m_localAabbMax.set(i, localAabbMax.get(i));
            }
        }
    }

    @Override
    public void setLocalScaling(btVector3 scaling) {
        for (int i = 0; i < m_children.size(); i++) {
            btTransform childTrans = new btTransform(getChildTransform(i));
            btVector3 childScale = new btVector3(m_children.get(i).m_childShape.getLocalScaling());
            childScale.set(childScale.mul(scaling).div(m_localScaling));
            m_children.get(i).m_childShape.setLocalScaling(childScale);
            childTrans.setOrigin(childTrans.getOrigin().mul(scaling).div(m_localScaling));
            updateChildTransform(i, childTrans, false);
        }

        m_localScaling.set(scaling);
        recalculateLocalAabb();
    }

    @Override
    public btVector3 getLocalScaling() {
        return m_localScaling;
    }

    @Override
    public void calculateLocalInertia(double mass, btVector3 inertia) {
        // approximation: take the inertia from the aabb for now
        btTransform ident = new btTransform();
        ident.setIdentity();
        btVector3 aabbMin = new btVector3(), aabbMax = new btVector3();
        getAabb(ident, aabbMin, aabbMax);

        btVector3 halfExtents = aabbMax.sub(aabbMin).mul(0.5);

        double lx = 2.0 * (halfExtents.x());
        double ly = 2.0 * (halfExtents.y());
        double lz = 2.0 * (halfExtents.z());

        inertia.set(0, mass / (12.0) * (ly * ly + lz * lz));
        inertia.set(1, mass / (12.0) * (lx * lx + lz * lz));
        inertia.set(2, mass / (12.0) * (lx * lx + ly * ly));
    }

    @Override
    public void setMargin(double margin) {
        m_collisionMargin = margin;
    }

    @Override
    public double getMargin() {
        return m_collisionMargin;
    }

    @Override
    public String getName() {
        return "Compound";
    }

    public btDbvt getDynamicAabbTree() {
        return m_dynamicAabbTree;
    }

    public void createAabbTreeFromChildren() {
        if (m_dynamicAabbTree == null) {
            m_dynamicAabbTree = new btDbvt();

            for (int index = 0; index < m_children.size(); index++) {
                btCompoundShapeChild child = m_children.get(index);

                // extend the local aabbMin/aabbMax
                btVector3 localAabbMin = new btVector3(), localAabbMax = new btVector3();
                child.m_childShape.getAabb(child.m_transform, localAabbMin, localAabbMax);

                btDbvtAabbMm bounds = btDbvtAabbMm.FromMM(localAabbMin, localAabbMax);
                child.m_node = m_dynamicAabbTree.insert(bounds, Integer.valueOf(index));
            }
        }
    }

    /**
     * computes the exact moment of inertia and the transform from the coordinate system defined by
     * the principal axes of the moment of inertia and the center of mass to the current coordinate
     * system. "masses" points to an array of masses of the children.
     */
    public void calculatePrincipalAxisTransform(
            double[] masses, btTransform principal, btVector3 inertia) {
        int n = m_children.size();

        double totalMass = 0;
        btVector3 center = new btVector3(0, 0, 0);
        int k;

        for (k = 0; k < n; k++) {
            center.addLocal(m_children.get(k).m_transform.getOrigin().mul(masses[k]));
            totalMass += masses[k];
        }

        center.divLocal(totalMass);
        principal.setOrigin(center);

        btMatrix3x3 tensor = new btMatrix3x3(0, 0, 0, 0, 0, 0, 0, 0, 0);
        for (k = 0; k < n; k++) {
            btVector3 i = new btVector3();
            m_children.get(k).m_childShape.calculateLocalInertia(masses[k], i);

            btTransform t = m_children.get(k).m_transform;
            btVector3 o = t.getOrigin().sub(center);

            // compute inertia tensor in coordinate system of compound shape
            btMatrix3x3 j = t.getBasis().transpose();
            j.get(0).mulLocal(i.get(0));
            j.get(1).mulLocal(i.get(1));
            j.get(2).mulLocal(i.get(2));
            j.set(t.getBasis().mul(j));

            // add inertia tensor
            tensor.get(0).addLocal(j.get(0));
            tensor.get(1).addLocal(j.get(1));
            tensor.get(2).addLocal(j.get(2));

            // compute inertia tensor of pointmass at o
            double o2 = o.length2();
            j.get(0).setValue(o2, 0, 0);
            j.get(1).setValue(0, o2, 0);
            j.get(2).setValue(0, 0, o2);
            j.get(0).addLocal(o.mul(-o.x()));
            j.get(1).addLocal(o.mul(-o.y()));
            j.get(2).addLocal(o.mul(-o.z()));

            // add inertia tensor of pointmass
            tensor.get(0).addLocal(j.get(0).mul(masses[k]));
            tensor.get(1).addLocal(j.get(1).mul(masses[k]));
            tensor.get(2).addLocal(j.get(2).mul(masses[k]));
        }

        tensor.diagonalize(principal.getBasis(), 0.00001, 20);
        inertia.setValue(tensor.get(0, 0), tensor.get(1, 1), tensor.get(2, 2));
    }

    public int getUpdateRevision() {
        return m_updateRevision;
    }
}
