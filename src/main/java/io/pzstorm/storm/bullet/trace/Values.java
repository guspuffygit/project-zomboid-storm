package io.pzstorm.storm.bullet.trace;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Snapshotting, bit-exact comparison and hex formatting of JNI values. */
public final class Values {

    /** How many array elements {@link #describe} prints before eliding. */
    public static final int PREVIEW = 8;

    private Values() {}

    /** An immutable-by-convention copy of a live argument/return value. */
    public static Object snapshot(ValueKind kind, Object v) {
        if (v == null) {
            return null;
        }
        return switch (kind) {
            case FLOAT_ARRAY -> ((float[]) v).clone();
            case INT_ARRAY -> ((int[]) v).clone();
            case BYTE_BUFFER -> BufferSnapshot.of((ByteBuffer) v);
            default -> v;
        };
    }

    /** A fresh live value from a snapshot (arrays copied so the callee can write into them). */
    public static Object materialize(ValueKind kind, Object snap) {
        if (snap == null) {
            return null;
        }
        return switch (kind) {
            case FLOAT_ARRAY -> ((float[]) snap).clone();
            case INT_ARRAY -> ((int[]) snap).clone();
            case BYTE_BUFFER -> ((BufferSnapshot) snap).materialize();
            default -> snap;
        };
    }

    /** Bit-exact equality: floats by raw bits, strings exactly, arrays element-wise. */
    public static boolean same(ValueKind kind, Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return switch (kind) {
            case FLOAT -> Float.floatToRawIntBits((Float) a) == Float.floatToRawIntBits((Float) b);
            case FLOAT_ARRAY -> firstDifference((float[]) a, (float[]) b) < 0;
            case INT_ARRAY -> Arrays.equals((int[]) a, (int[]) b);
            default -> Objects.equals(a, b);
        };
    }

    /** Index of the first element whose raw bits differ, -1 if identical (length counts). */
    public static int firstDifference(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
                return i;
            }
        }
        return a.length == b.length ? -1 : n;
    }

    public static int firstDifference(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return a.length == b.length ? -1 : n;
    }

    public static int countDifferences(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        int c = Math.abs(a.length - b.length);
        for (int i = 0; i < n; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
                c++;
            }
        }
        return c;
    }

    /** Short human-readable rendering with hex bits for floats. */
    public static String describe(ValueKind kind, Object v) {
        if (v == null) {
            return kind == ValueKind.VOID ? "void" : "null";
        }
        return switch (kind) {
            case FLOAT -> floatHex((Float) v);
            case STRING -> quote((String) v);
            case FLOAT_ARRAY -> describeArray((float[]) v, 0);
            case INT_ARRAY -> "int[" + ((int[]) v).length + "]" + preview((int[]) v, 0);
            default -> String.valueOf(v);
        };
    }

    public static String floatHex(float f) {
        return f + "(0x" + String.format("%08x", Float.floatToRawIntBits(f)) + ")";
    }

    public static String describeArray(float[] a, int from) {
        StringBuilder sb = new StringBuilder("float[" + a.length + "]{");
        int start = Math.max(0, Math.min(from, a.length));
        if (start > 0) {
            sb.append("..@").append(start).append(": ");
        }
        for (int i = start; i < a.length && i < start + PREVIEW; i++) {
            if (i > start) sb.append(", ");
            sb.append(floatHex(a[i]));
        }
        if (a.length > start + PREVIEW) sb.append(", ..");
        return sb.append('}').toString();
    }

    static String preview(int[] a, int from) {
        StringBuilder sb = new StringBuilder("{");
        int start = Math.max(0, Math.min(from, a.length));
        if (start > 0) {
            sb.append("..@").append(start).append(": ");
        }
        for (int i = start; i < a.length && i < start + PREVIEW; i++) {
            if (i > start) sb.append(", ");
            sb.append(a[i]);
        }
        if (a.length > start + PREVIEW) sb.append(", ..");
        return sb.append('}').toString();
    }

    static String quote(String s) {
        String t = s.length() > 200 ? s.substring(0, 200) + "..(" + s.length() + " chars)" : s;
        return '"' + t.replace("\n", "\\n") + '"';
    }

    /** Detail of how two values differ (index + hex bits for arrays). */
    public static String explain(ValueKind kind, Object expected, Object actual) {
        if (expected == null || actual == null) {
            return "expected " + describe(kind, expected) + " actual " + describe(kind, actual);
        }
        switch (kind) {
            case FLOAT_ARRAY -> {
                float[] e = (float[]) expected;
                float[] a = (float[]) actual;
                int i = firstDifference(e, a);
                String where =
                        e.length != a.length
                                ? "length expected " + e.length + " actual " + a.length + "; "
                                : "";
                if (i < Math.min(e.length, a.length)) {
                    where +=
                            "first diff at ["
                                    + i
                                    + "] expected "
                                    + floatHex(e[i])
                                    + " actual "
                                    + floatHex(a[i])
                                    + "; "
                                    + countDifferences(e, a)
                                    + " element(s) differ";
                }
                return where
                        + "\n      expected "
                        + describeArray(e, Math.max(0, i - 2))
                        + "\n      actual   "
                        + describeArray(a, Math.max(0, i - 2));
            }
            case INT_ARRAY -> {
                int[] e = (int[]) expected;
                int[] a = (int[]) actual;
                int i = firstDifference(e, a);
                return "first diff at ["
                        + i
                        + "] lengths "
                        + e.length
                        + "/"
                        + a.length
                        + (i < Math.min(e.length, a.length)
                                ? " expected " + e[i] + " actual " + a[i]
                                : "");
            }
            case BYTE_BUFFER -> {
                BufferSnapshot e = (BufferSnapshot) expected;
                BufferSnapshot a = (BufferSnapshot) actual;
                int i = Arrays.mismatch(e.content(), a.content());
                return "expected " + e + " actual " + a + (i >= 0 ? " first byte diff @" + i : "");
            }
            default -> {
                return "expected " + describe(kind, expected) + " actual " + describe(kind, actual);
            }
        }
    }

    /** Default answer for an upcall the replayer cannot match to the recording. */
    public static Object defaultUpcallResult(Sig sig, Object[] args) {
        if (sig == BulletApi.GET_BONE_NAME) {
            return SkeletonBoneTable.getBoneName((Integer) args[0]);
        }
        if (sig == BulletApi.GET_BONE_ORDINAL) {
            return SkeletonBoneTable.getBoneOrdinal((String) args[0]);
        }
        return switch (sig.ret) {
            case BOOLEAN -> Boolean.FALSE;
            case INT -> 0;
            case FLOAT -> 0f;
            default -> null;
        };
    }
}
