// Port of LinearMath/btHashMap.h class btHashInt (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/** int key with Thomas Wang's hash. */
public class btHashInt implements btHashMap.HashKey<btHashInt> {
    public int m_uid;

    public btHashInt(int uid) {
        m_uid = uid;
    }

    public int getUid1() {
        return m_uid;
    }

    public void setUid1(int uid) {
        m_uid = uid;
    }

    @Override
    public boolean equals(btHashInt other) {
        return getUid1() == other.getUid1();
    }

    @Override
    public int getHash() {
        return btHashMap.wangHash(m_uid);
    }

    @Override
    public btHashInt copyKey() {
        return new btHashInt(m_uid);
    }
}
