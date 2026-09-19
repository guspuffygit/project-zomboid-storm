// Port of LinearMath/btHashMap.h struct btHashString (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

import java.nio.charset.StandardCharsets;

/**
 * C-string key with FNV-1 hash. The hash runs over the string's bytes (UTF-8 here; C++ sees
 * whatever bytes the caller passed) as signed {@code char} (x86-64 GCC), stopping at the first NUL,
 * exactly as C++.
 */
public class btHashString implements btHashMap.HashKey<btHashString> {
    public final String m_string;
    public final byte[] m_bytes;
    public final int m_hash;

    public btHashString(String name) {
        this(name, name.getBytes(StandardCharsets.UTF_8));
    }

    public btHashString(String name, byte[] bytes) {
        m_string = name;
        m_bytes = bytes;
        final int InitialFNV = 0x811C9DC5; // 2166136261u
        final int FNVMultiple = 16777619;
        int hash = InitialFNV;
        for (int i = 0; i < m_bytes.length && m_bytes[i] != 0; i++) {
            hash = hash ^ (m_bytes[i]); // signed char, sign-extended
            hash *= FNVMultiple;
        }
        m_hash = hash;
    }

    @Override
    public int getHash() {
        return m_hash;
    }

    private static int at(byte[] s, int i) {
        return i < s.length ? (s[i] & 0xFF) : 0;
    }

    /** strcmp-like on unsigned chars; returns -1, 0, 1. */
    public int portableStringCompare(byte[] src, byte[] dst) {
        int ret;
        int i = 0;
        while ((ret = at(src, i) - at(dst, i)) == 0 && at(dst, i) != 0) {
            ++i;
        }
        if (ret < 0) ret = -1;
        else if (ret > 0) ret = 1;
        return (ret);
    }

    @Override
    public boolean equals(btHashString other) {
        return (m_string == other.m_string) || (0 == portableStringCompare(m_bytes, other.m_bytes));
    }

    @Override
    public btHashString copyKey() {
        return this;
    }
}
