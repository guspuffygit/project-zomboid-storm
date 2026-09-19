package io.pzstorm.storm.bullet.trace;

import java.lang.reflect.Method;

/**
 * A native method or an upcall: name, JVM descriptor and the value kinds of its parameters and
 * return. {@code index} is the position in {@link BulletApi#ALL}.
 */
public final class Sig {

    public final int index;
    public final boolean upcall;
    public final String name;
    public final String descriptor;
    public final ValueKind[] params;
    public final ValueKind ret;
    public final Method method;

    Sig(int index, boolean upcall, Method method) {
        this.index = index;
        this.upcall = upcall;
        this.method = method;
        this.name = method.getName();
        Class<?>[] types = method.getParameterTypes();
        this.params = new ValueKind[types.length];
        for (int i = 0; i < types.length; i++) {
            params[i] = ValueKind.of(types[i]);
        }
        this.ret = ValueKind.of(method.getReturnType());
        this.descriptor = descriptorOf(method);
    }

    /** {@code name + descriptor}, unique across natives and upcalls. */
    public String key() {
        return name + descriptor;
    }

    public static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(typeDescriptor(p));
        }
        return sb.append(')').append(typeDescriptor(m.getReturnType())).toString();
    }

    static String typeDescriptor(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == int.class) return "I";
        if (c == float.class) return "F";
        if (c.isArray()) return "[" + typeDescriptor(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    /**
     * A signature known only from a trace file's table (e.g. a native a newer game version added):
     * kinds come from the descriptor; {@link #method} is null and {@link #index} is -1.
     */
    public Sig(boolean upcall, String name, String descriptor) {
        this.index = -1;
        this.upcall = upcall;
        this.method = null;
        this.name = name;
        this.descriptor = descriptor;
        java.util.List<ValueKind> ps = new java.util.ArrayList<>();
        int i = 1;
        while (descriptor.charAt(i) != ')') {
            int end = i;
            while (descriptor.charAt(end) == '[') end++;
            if (descriptor.charAt(end) == 'L') end = descriptor.indexOf(';', end);
            ps.add(kindOf(descriptor.substring(i, end + 1)));
            i = end + 1;
        }
        this.params = ps.toArray(new ValueKind[0]);
        this.ret = kindOf(descriptor.substring(i + 1));
    }

    private static ValueKind kindOf(String d) {
        for (ValueKind k : ValueKind.values()) {
            if (typeDescriptor(k.type).equals(d)) {
                return k;
            }
        }
        throw new IllegalArgumentException("unsupported JNI descriptor " + d);
    }

    @Override
    public String toString() {
        return (upcall ? "upcall " : "") + name + descriptor;
    }

    int[] mutableParamIndexes() {
        return java.util.stream.IntStream.range(0, params.length)
                .filter(i -> params[i].isMutable())
                .toArray();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sig s && s.upcall == upcall && s.key().equals(key());
    }

    @Override
    public int hashCode() {
        return key().hashCode() * 31 + (upcall ? 1 : 0);
    }
}
