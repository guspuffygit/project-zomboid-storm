// Port of LinearMath/btTransform.h (Bullet 2.82), BT_USE_DOUBLE_PRECISION.
package io.pzstorm.storm.bullet.linearmath;

/**
 * btTransform: rotation {@link #m_basis} + translation {@link #m_origin} (both final, mutated in
 * place; {@link #getBasis}/{@link #getOrigin} return them by reference like C++).
 *
 * <pre>
 * C++                               Java
 * --------------------------------  ---------------------------------------------
 * btTransform a(b);  a = b;         new btTransform(b);  a.set(b)
 * t(v)  / t * v                     t.transform(v)                  (new, w=0)
 * t * q  (btQuaternion)             t.mul(q)
 * t1 * t2                           t1.mul(t2)                      (new)
 * t *= t2                           t.mulLocal(t2)                  (returns this)
 * t.mult(t1, t2)                    t.mult(t1, t2)  == t.mul(t1, t2) (void, aliasing as C++)
 * t.inverse()                       t.inverse()                     (new)
 * t.inverseTimes(t2)                t.inverseTimes(t2)              (new)
 * t.invXform(v)                     t.invXform(v)                   (new)
 * getOpenGLMatrix(btScalar m[16])   getOpenGLMatrix(double[] m)
 * t1 == t2                          t1.equalsValue(t2)
 * </pre>
 *
 * Operation order within mult / operator*= follows C++ exactly, including the aliasing behaviour of
 * {@code mult(t1, t2)} when {@code this == t1} (basis written before the origin is computed).
 */
public class btTransform {
    public final btMatrix3x3 m_basis = new btMatrix3x3();
    public final btVector3 m_origin = new btVector3();

    private static final btTransform identityTransform = new btTransform(btMatrix3x3.getIdentity());

    /** C++ leaves storage uninitialised; Java zeroes it. */
    public btTransform() {}

    public btTransform(btQuaternion q) {
        m_basis.setRotation(q);
        m_origin.setValue(0, 0, 0);
    }

    public btTransform(btQuaternion q, btVector3 c) {
        m_basis.setRotation(q);
        m_origin.set(c);
    }

    public btTransform(btMatrix3x3 b) {
        m_basis.set(b);
        m_origin.setValue(0, 0, 0);
    }

    public btTransform(btMatrix3x3 b, btVector3 c) {
        m_basis.set(b);
        m_origin.set(c);
    }

    public btTransform(btTransform other) {
        m_basis.set(other.m_basis);
        m_origin.set(other.m_origin);
    }

    /** {@code operator=} */
    public btTransform set(btTransform other) {
        m_basis.set(other.m_basis);
        m_origin.set(other.m_origin);
        return this;
    }

    /** {@code void mult(t1, t2)}: this = t1 * t2. */
    public void mult(btTransform t1, btTransform t2) {
        m_basis.set(t1.m_basis.mul(t2.m_basis));
        m_origin.set(t1.transform(t2.m_origin));
    }

    /** Alias of {@link #mult}. */
    public void mul(btTransform t1, btTransform t2) {
        mult(t1, t2);
    }

    /** {@code operator()(x)}: {@code x.dot3(m_basis[0], m_basis[1], m_basis[2]) + m_origin}. */
    public btVector3 transform(btVector3 x) {
        return x.dot3(m_basis.m_el0, m_basis.m_el1, m_basis.m_el2).add(m_origin);
    }

    /** {@code operator*(const btVector3&)} */
    public btVector3 mul(btVector3 x) {
        return transform(x);
    }

    /** {@code operator*(const btQuaternion&)}: {@code getRotation() * q}. */
    public btQuaternion mul(btQuaternion q) {
        return getRotation().mul(q);
    }

    public btMatrix3x3 getBasis() {
        return m_basis;
    }

    public btVector3 getOrigin() {
        return m_origin;
    }

    public btQuaternion getRotation() {
        btQuaternion q = new btQuaternion();
        m_basis.getRotation(q);
        return q;
    }

    public void setFromOpenGLMatrix(double[] m) {
        m_basis.setFromOpenGLSubMatrix(m, 0);
        m_origin.setValue(m[12], m[13], m[14]);
    }

    public void getOpenGLMatrix(double[] m) {
        m_basis.getOpenGLSubMatrix(m, 0);
        m[12] = m_origin.x();
        m[13] = m_origin.y();
        m[14] = m_origin.z();
        m[15] = 1.0;
    }

    /** {@code m_origin = origin} (copies w). */
    public void setOrigin(btVector3 origin) {
        m_origin.set(origin);
    }

    public btVector3 invXform(btVector3 inVec) {
        btVector3 v = inVec.sub(m_origin);
        return m_basis.transpose().mul(v);
    }

    public void setBasis(btMatrix3x3 basis) {
        m_basis.set(basis);
    }

    public void setRotation(btQuaternion q) {
        m_basis.setRotation(q);
    }

    public void setIdentity() {
        m_basis.setIdentity();
        m_origin.setValue(0.0, 0.0, 0.0);
    }

    /** {@code operator*=}: {@code m_origin += m_basis * t.m_origin; m_basis *= t.m_basis;} */
    public btTransform mulLocal(btTransform t) {
        m_origin.addLocal(m_basis.mul(t.m_origin));
        m_basis.mulLocal(t.m_basis);
        return this;
    }

    public btTransform inverse() {
        btMatrix3x3 inv = m_basis.transpose();
        return new btTransform(inv, inv.mul(m_origin.negate()));
    }

    public btTransform inverseTimes(btTransform t) {
        btVector3 v = t.getOrigin().sub(m_origin);
        return new btTransform(m_basis.transposeTimes(t.m_basis), btMatrix3x3.mul(v, m_basis));
    }

    /** {@code operator*(const btTransform&)} */
    public btTransform mul(btTransform t) {
        return new btTransform(m_basis.mul(t.m_basis), transform(t.m_origin));
    }

    /** Shared constant (C++ returns a const reference; do not mutate). */
    public static btTransform getIdentity() {
        return identityTransform;
    }

    /** {@code operator==} */
    public boolean equalsValue(btTransform t2) {
        return m_basis.equalsValue(t2.m_basis) && m_origin.equalsValue(t2.m_origin);
    }

    /** btTransformDoubleData: basis (12 doubles) then origin (4 doubles). */
    public void serialize(double[] dataOut, int off) {
        m_basis.serialize(dataOut, off);
        m_origin.serialize(dataOut, off + 12);
    }

    public void serializeFloat(float[] dataOut, int off) {
        m_basis.serializeFloat(dataOut, off);
        m_origin.serializeFloat(dataOut, off + 12);
    }

    public void deSerialize(double[] dataIn, int off) {
        m_basis.deSerialize(dataIn, off);
        m_origin.deSerialize(dataIn, off + 12);
    }

    public void deSerializeFloat(float[] dataIn, int off) {
        m_basis.deSerializeFloat(dataIn, off);
        m_origin.deSerializeFloat(dataIn, off + 12);
    }

    @Override
    public String toString() {
        return "btTransform[basis=" + m_basis + ", origin=" + m_origin + "]";
    }
}
