// Port of LinearMath/btScalar.h struct btTypedObject (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/** Rudimentary class to provide type info (base of btTypedConstraint and btPersistentManifold). */
public class btTypedObject {
    public int m_objectType;

    public btTypedObject(int objectType) {
        m_objectType = objectType;
    }

    public int getObjectType() {
        return m_objectType;
    }
}
