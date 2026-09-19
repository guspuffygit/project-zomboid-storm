package io.pzstorm.storm.bullet.collision.dispatch;

/**
 * Port of Bullet 2.82 {@code struct btSimplePair} (btHashedSimplePairCache.h).
 *
 * <p>C++ has {@code union { void* m_userPointer; int m_userValue; }}. Java keeps two fields; the
 * constructor / {@link #set} keep them consistent the way the union would (writing a null pointer
 * zeroes the int view). Nothing linked reads {@code m_userValue}.
 */
public class btSimplePair {
    public int m_indexA;
    public int m_indexB;
    public Object m_userPointer;
    public int m_userValue;

    /** Value-array slot (uninitialized storage in C++). */
    public btSimplePair() {}

    public btSimplePair(int indexA, int indexB) {
        m_indexA = indexA;
        m_indexB = indexB;
        m_userPointer = null;
        m_userValue = 0;
    }

    /** {@code *this = other} (trivial copy of the struct, union included). */
    public btSimplePair set(btSimplePair other) {
        m_indexA = other.m_indexA;
        m_indexB = other.m_indexB;
        m_userPointer = other.m_userPointer;
        m_userValue = other.m_userValue;
        return this;
    }
}
