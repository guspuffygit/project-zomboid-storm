// Port of LinearMath/btHashMap.h class btHashPtr (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Pointer key. C++ hashes the 64-bit pointer value as {@code m_hashValues[0] + m_hashValues[1]}
 * (low + high 32 bits, little-endian) then Wang's hash, so the Java side needs the emulated address
 * ({@link btGlobals#nextAddress}) of the pointee: PTR-ORDER site (hash-table iteration order and
 * bucket chains depend on addresses). Equality is pointer identity: same object and same address.
 */
public class btHashPtr implements btHashMap.HashKey<btHashPtr> {
    public final Object m_pointer;
    public final long m_address;

    public btHashPtr(Object pointer, long address) {
        m_pointer = pointer;
        m_address = address;
    }

    /** Address-only key (pointer identity == address identity). */
    public btHashPtr(long address) {
        this(null, address);
    }

    public Object getPointer() {
        return m_pointer;
    }

    @Override
    public boolean equals(btHashPtr other) {
        return m_pointer == other.m_pointer && m_address == other.m_address;
    }

    @Override
    public int getHash() {
        // PTR-ORDER: hash of the emulated address
        int key = (int) m_address + (int) (m_address >>> 32);
        return btHashMap.wangHash(key);
    }

    @Override
    public btHashPtr copyKey() {
        return this;
    }
}
