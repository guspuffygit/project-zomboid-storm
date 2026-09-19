// Port of LinearMath/btHashMap.h template class btHashKey<Value> (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/** int key with Thomas Wang's hash; the Value type parameter is unused (as in C++). */
public class btHashKey<Value> implements btHashMap.HashKey<btHashKey<Value>> {
    public final int m_uid;

    public btHashKey(int uid) {
        m_uid = uid;
    }

    public int getUid1() {
        return m_uid;
    }

    @Override
    public boolean equals(btHashKey<Value> other) {
        return getUid1() == other.getUid1();
    }

    @Override
    public int getHash() {
        return btHashMap.wangHash(m_uid);
    }

    @Override
    public btHashKey<Value> copyKey() {
        return this;
    }
}
