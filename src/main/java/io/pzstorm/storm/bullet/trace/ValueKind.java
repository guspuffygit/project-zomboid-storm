package io.pzstorm.storm.bullet.trace;

import java.nio.ByteBuffer;

/** The value types that cross the {@code Bullet} JNI boundary. */
public enum ValueKind {
    VOID('V', void.class),
    BOOLEAN('Z', boolean.class),
    INT('I', int.class),
    FLOAT('F', float.class),
    STRING('S', String.class),
    FLOAT_ARRAY('f', float[].class),
    INT_ARRAY('i', int[].class),
    BYTE_BUFFER('B', ByteBuffer.class);

    public final char code;
    public final Class<?> type;

    ValueKind(char code, Class<?> type) {
        this.code = code;
        this.type = type;
    }

    /** True for arguments the callee may write into (recorded again after the call). */
    public boolean isMutable() {
        return this == FLOAT_ARRAY || this == INT_ARRAY || this == BYTE_BUFFER;
    }

    public static ValueKind of(Class<?> type) {
        for (ValueKind k : values()) {
            if (k.type == type) {
                return k;
            }
        }
        throw new IllegalArgumentException("unsupported JNI value type " + type);
    }
}
