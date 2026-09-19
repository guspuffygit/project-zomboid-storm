// Port of LinearMath/btConvexHullComputer.cpp, class btConvexHullInternal (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Internal exact-arithmetic hull builder behind {@link btConvexHullComputer}. Faithful port of the
 * portable (non {@code USE_X86_64_ASM}) code path: the PZ binary's {@code Rational64::compare} and
 * {@code Int128::mul} are the C++ fallbacks (DMul), not the inline asm (verified in nogl.asm at
 * 0x11b0a0 / 0x11af10).
 *
 * <p>Integer semantics: {@code int32_t} arithmetic is Java {@code int} (wrapping), {@code int64_t}
 * is {@code long}, {@code uint64_t} is {@code long} with unsigned compares/conversions. {@code
 * Point32::cross(Point32)} and {@code Point32::dot(Point32)} are computed in 32 bits and then
 * sign-extended, exactly as the C++ types say (the input scaling to +-5108 keeps them in range).
 * {@code (int32_t)} casts of doubles use {@link btScalar#cvttsd2si}.
 *
 * <p>Pools: {@link Pool}/{@link PoolArray} keep the C++ free-list behaviour (LIFO reuse of freed
 * objects, arrays of {@code arraySize} objects, {@link btGlobals#btAlignedAlloc} counted per
 * PoolArray). Nothing in the algorithm compares pool addresses except {@code Vertex* w = v + 1} in
 * {@code computeInternal} case 2, which is {@code originalVertices[start + 1]} because all vertices
 * of one compute() live contiguously in a single PoolArray of size {@code count}.
 *
 * <p>Value types ({@link Point32}, {@link Point64}, {@link Int128}, ...) are mutable Java objects;
 * every C++ copy is an explicit copy here.
 */
public class btConvexHullInternal {

    // ---------------------------------------------------------------------------------------
    // Point64
    // ---------------------------------------------------------------------------------------
    public static class Point64 {
        public long x;
        public long y;
        public long z;

        public Point64(long x, long y, long z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public boolean isZero() {
            return (x == 0) && (y == 0) && (z == 0);
        }

        public long dot(Point64 b) {
            return x * b.x + y * b.y + z * b.z;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Point32
    // ---------------------------------------------------------------------------------------
    public static class Point32 {
        public int x;
        public int y;
        public int z;
        public int index;

        /** C++ leaves members uninitialised; zero here. */
        public Point32() {}

        public Point32(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.index = -1;
        }

        public Point32(Point32 o) {
            set(o);
        }

        /** Implicit copy-assignment. */
        public Point32 set(Point32 o) {
            x = o.x;
            y = o.y;
            z = o.z;
            index = o.index;
            return this;
        }

        /** {@code operator==} (index ignored). */
        public boolean equalsValue(Point32 b) {
            return (x == b.x) && (y == b.y) && (z == b.z);
        }

        /** {@code operator!=} */
        public boolean notEquals(Point32 b) {
            return (x != b.x) || (y != b.y) || (z != b.z);
        }

        public boolean isZero() {
            return (x == 0) && (y == 0) && (z == 0);
        }

        /** int32 arithmetic, widened to int64 by the Point64 constructor. */
        public Point64 cross(Point32 b) {
            return new Point64(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x);
        }

        public Point64 cross(Point64 b) {
            return new Point64(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x);
        }

        /** int32 arithmetic, then sign-extended to int64. */
        public long dot(Point32 b) {
            return x * b.x + y * b.y + z * b.z;
        }

        public long dot(Point64 b) {
            return x * b.x + y * b.y + z * b.z;
        }

        /** {@code operator+} */
        public Point32 add(Point32 b) {
            return new Point32(x + b.x, y + b.y, z + b.z);
        }

        /** {@code operator-} */
        public Point32 sub(Point32 b) {
            return new Point32(x - b.x, y - b.y, z - b.z);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Int128 (two's complement, low/high are uint64_t)
    // ---------------------------------------------------------------------------------------
    public static class Int128 {
        public long low;
        public long high;

        /** C++ leaves members uninitialised; zero here. */
        public Int128() {}

        public Int128(long low, long high) {
            this.low = low;
            this.high = high;
        }

        public Int128(Int128 o) {
            this.low = o.low;
            this.high = o.high;
        }

        /** {@code Int128(uint64_t low)}: high = 0. */
        public static Int128 fromUnsigned(long low) {
            return new Int128(low, 0);
        }

        /** {@code Int128(int64_t value)}: sign-extended. */
        public static Int128 fromSigned(long value) {
            return new Int128(value, (value >= 0) ? 0 : -1L);
        }

        public Int128 set(Int128 o) {
            low = o.low;
            high = o.high;
            return this;
        }

        /** {@code static Int128 mul(int64_t a, int64_t b)} (portable path). */
        public static Int128 mul(long a, long b) {
            Int128 result = new Int128();
            boolean negative = a < 0;
            if (negative) {
                a = -a;
            }
            if (b < 0) {
                negative = !negative;
                b = -b;
            }
            long[] lh = new long[2];
            DMul.mul64(a, b, lh);
            result.low = lh[0];
            result.high = lh[1];
            return negative ? result.negate() : result;
        }

        /** {@code static Int128 mul(uint64_t a, uint64_t b)} (portable path). */
        public static Int128 mulUnsigned(long a, long b) {
            Int128 result = new Int128();
            long[] lh = new long[2];
            DMul.mul64(a, b, lh);
            result.low = lh[0];
            result.high = lh[1];
            return result;
        }

        /** Unary {@code operator-}. */
        public Int128 negate() {
            return new Int128(-low, ~high + ((low == 0) ? 1 : 0));
        }

        /** {@code operator+} */
        public Int128 add(Int128 b) {
            long lo = low + b.low;
            return new Int128(lo, high + b.high + (Long.compareUnsigned(lo, low) < 0 ? 1 : 0));
        }

        /** {@code operator-}: {@code *this + -b}. */
        public Int128 sub(Int128 b) {
            return this.add(b.negate());
        }

        /** {@code operator+=} */
        public Int128 addLocal(Int128 b) {
            long lo = low + b.low;
            if (Long.compareUnsigned(lo, low) < 0) {
                ++high;
            }
            low = lo;
            high += b.high;
            return this;
        }

        /** {@code operator++} (prefix). */
        public Int128 increment() {
            if (++low == 0) {
                ++high;
            }
            return this;
        }

        /** {@code Int128 operator*(int64_t b) const} */
        public Int128 mul(long b) {
            boolean negative = high < 0;
            Int128 a = negative ? this.negate() : new Int128(this);
            if (b < 0) {
                negative = !negative;
                b = -b;
            }
            Int128 result = mulUnsigned(a.low, b);
            result.high += a.high * b;
            return negative ? result.negate() : result;
        }

        public double toScalar() {
            return (high >= 0)
                    ? (double) high * ((double) 0x100000000L * (double) 0x100000000L)
                            + btScalar.u64ToDouble(low)
                    : -(this.negate()).toScalar();
        }

        public int getSign() {
            return (high < 0) ? -1 : (high != 0 || low != 0) ? 1 : 0;
        }

        /** {@code operator<} (unsigned on both words). */
        public boolean lessThan(Int128 b) {
            return (Long.compareUnsigned(high, b.high) < 0)
                    || ((high == b.high) && (Long.compareUnsigned(low, b.low) < 0));
        }

        public int ucmp(Int128 b) {
            if (Long.compareUnsigned(high, b.high) < 0) {
                return -1;
            }
            if (Long.compareUnsigned(high, b.high) > 0) {
                return 1;
            }
            if (Long.compareUnsigned(low, b.low) < 0) {
                return -1;
            }
            if (Long.compareUnsigned(low, b.low) > 0) {
                return 1;
            }
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rational64
    // ---------------------------------------------------------------------------------------
    public static class Rational64 {
        long m_numerator; // uint64_t
        long m_denominator; // uint64_t
        int sign;

        public Rational64(long numerator, long denominator) {
            if (numerator > 0) {
                sign = 1;
                m_numerator = numerator;
            } else if (numerator < 0) {
                sign = -1;
                m_numerator = -numerator;
            } else {
                sign = 0;
                m_numerator = 0;
            }
            if (denominator > 0) {
                m_denominator = denominator;
            } else if (denominator < 0) {
                sign = -sign;
                m_denominator = -denominator;
            } else {
                m_denominator = 0;
            }
        }

        /** Implicit copy-assignment. */
        public Rational64 set(Rational64 o) {
            m_numerator = o.m_numerator;
            m_denominator = o.m_denominator;
            sign = o.sign;
            return this;
        }

        public boolean isNegativeInfinity() {
            return (sign < 0) && (m_denominator == 0);
        }

        public boolean isNaN() {
            return (sign == 0) && (m_denominator == 0);
        }

        /** Portable path: {@code sign * mul(num, b.den).ucmp(mul(den, b.num))}. */
        public int compare(Rational64 b) {
            if (sign != b.sign) {
                return sign - b.sign;
            } else if (sign == 0) {
                return 0;
            }
            return sign
                    * Int128.mulUnsigned(m_numerator, b.m_denominator)
                            .ucmp(Int128.mulUnsigned(m_denominator, b.m_numerator));
        }

        public double toScalar() {
            return sign
                    * ((m_denominator == 0)
                            ? btScalar.SIMD_INFINITY
                            : btScalar.u64ToDouble(m_numerator)
                                    / btScalar.u64ToDouble(m_denominator));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rational128
    // ---------------------------------------------------------------------------------------
    public static class Rational128 {
        final Int128 numerator = new Int128();
        final Int128 denominator = new Int128();
        int sign;
        boolean isInt64;

        public Rational128(long value) {
            if (value > 0) {
                sign = 1;
                this.numerator.set(Int128.fromSigned(value));
            } else if (value < 0) {
                sign = -1;
                this.numerator.set(Int128.fromSigned(-value));
            } else {
                sign = 0;
                this.numerator.set(Int128.fromUnsigned(0));
            }
            this.denominator.set(Int128.fromUnsigned(1));
            isInt64 = true;
        }

        public Rational128(Int128 numerator, Int128 denominator) {
            sign = numerator.getSign();
            if (sign >= 0) {
                this.numerator.set(numerator);
            } else {
                this.numerator.set(numerator.negate());
            }
            int dsign = denominator.getSign();
            if (dsign >= 0) {
                this.denominator.set(denominator);
            } else {
                sign = -sign;
                this.denominator.set(denominator.negate());
            }
            isInt64 = false;
        }

        /** Implicit copy-assignment. */
        public Rational128 set(Rational128 o) {
            numerator.set(o.numerator);
            denominator.set(o.denominator);
            sign = o.sign;
            isInt64 = o.isInt64;
            return this;
        }

        public int compare(Rational128 b) {
            if (sign != b.sign) {
                return sign - b.sign;
            } else if (sign == 0) {
                return 0;
            }
            if (isInt64) {
                return -b.compare(sign * numerator.low);
            }

            Int128 nbdLow = new Int128(),
                    nbdHigh = new Int128(),
                    dbnLow = new Int128(),
                    dbnHigh = new Int128();
            DMul.mul128(numerator, b.denominator, nbdLow, nbdHigh);
            DMul.mul128(denominator, b.numerator, dbnLow, dbnHigh);

            int cmp = nbdHigh.ucmp(dbnHigh);
            if (cmp != 0) {
                return cmp * sign;
            }
            return nbdLow.ucmp(dbnLow) * sign;
        }

        public int compare(long b) {
            if (isInt64) {
                long a = sign * numerator.low;
                return (a > b) ? 1 : (a < b) ? -1 : 0;
            }
            if (b > 0) {
                if (sign <= 0) {
                    return -1;
                }
            } else if (b < 0) {
                if (sign >= 0) {
                    return 1;
                }
                b = -b;
            } else {
                return sign;
            }

            return numerator.ucmp(denominator.mul(b)) * sign;
        }

        public double toScalar() {
            return sign
                    * ((denominator.getSign() == 0)
                            ? btScalar.SIMD_INFINITY
                            : numerator.toScalar() / denominator.toScalar());
        }
    }

    // ---------------------------------------------------------------------------------------
    // PointR128
    // ---------------------------------------------------------------------------------------
    public static class PointR128 {
        public final Int128 x = new Int128();
        public final Int128 y = new Int128();
        public final Int128 z = new Int128();
        public final Int128 denominator = new Int128();

        public PointR128() {}

        public PointR128(Int128 x, Int128 y, Int128 z, Int128 denominator) {
            this.x.set(x);
            this.y.set(y);
            this.z.set(z);
            this.denominator.set(denominator);
        }

        public PointR128 set(PointR128 o) {
            x.set(o.x);
            y.set(o.y);
            z.set(o.z);
            denominator.set(o.denominator);
            return this;
        }

        public double xvalue() {
            return x.toScalar() / denominator.toScalar();
        }

        public double yvalue() {
            return y.toScalar() / denominator.toScalar();
        }

        public double zvalue() {
            return z.toScalar() / denominator.toScalar();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Pool support
    // ---------------------------------------------------------------------------------------
    /** An object that can live in a {@link Pool}; {@code next} doubles as the free-list link. */
    public interface Poolable<T> {
        T poolNext();

        void setPoolNext(T n);

        /** {@code new(o) T()} */
        void construct();
    }

    public static class Vertex implements Poolable<Vertex> {
        public Vertex next;
        public Vertex prev;
        public Edge edges;
        public Face firstNearbyFace;
        public Face lastNearbyFace;
        public final PointR128 point128 = new PointR128();
        public final Point32 point = new Point32();
        public int copy;

        public Vertex() {
            construct();
        }

        /** Vertex(): the listed members; point/point128 are left as they were (uninitialised). */
        @Override
        public void construct() {
            next = null;
            prev = null;
            edges = null;
            firstNearbyFace = null;
            lastNearbyFace = null;
            copy = -1;
        }

        @Override
        public Vertex poolNext() {
            return next;
        }

        @Override
        public void setPoolNext(Vertex n) {
            next = n;
        }

        /** {@code operator-} */
        public Point32 sub(Vertex b) {
            return point.sub(b.point);
        }

        public Rational128 dot(Point64 b) {
            return (point.index >= 0)
                    ? new Rational128(point.dot(b))
                    : new Rational128(
                            point128.x.mul(b.x).add(point128.y.mul(b.y)).add(point128.z.mul(b.z)),
                            point128.denominator);
        }

        public double xvalue() {
            return (point.index >= 0) ? (double) point.x : point128.xvalue();
        }

        public double yvalue() {
            return (point.index >= 0) ? (double) point.y : point128.yvalue();
        }

        public double zvalue() {
            return (point.index >= 0) ? (double) point.z : point128.zvalue();
        }

        public void receiveNearbyFaces(Vertex src) {
            if (lastNearbyFace != null) {
                lastNearbyFace.nextWithSameNearbyVertex = src.firstNearbyFace;
            } else {
                firstNearbyFace = src.firstNearbyFace;
            }
            if (src.lastNearbyFace != null) {
                lastNearbyFace = src.lastNearbyFace;
            }
            for (Face f = src.firstNearbyFace; f != null; f = f.nextWithSameNearbyVertex) {
                f.nearbyVertex = this;
            }
            src.firstNearbyFace = null;
            src.lastNearbyFace = null;
        }
    }

    public static class Edge implements Poolable<Edge> {
        public Edge next;
        public Edge prev;
        public Edge reverse;
        public Vertex target;
        public Face face;
        public int copy;

        public Edge() {}

        /** {@code new(o) Edge()}: value-initialisation zeroes every member (no user ctor). */
        @Override
        public void construct() {
            next = null;
            prev = null;
            reverse = null;
            target = null;
            face = null;
            copy = 0;
        }

        /** ~Edge() */
        public void destruct() {
            next = null;
            prev = null;
            reverse = null;
            target = null;
            face = null;
        }

        @Override
        public Edge poolNext() {
            return next;
        }

        @Override
        public void setPoolNext(Edge n) {
            next = n;
        }

        public void link(Edge n) {
            next = n;
            n.prev = this;
        }
    }

    public static class Face implements Poolable<Face> {
        public Face next;
        public Vertex nearbyVertex;
        public Face nextWithSameNearbyVertex;
        public final Point32 origin = new Point32();
        public final Point32 dir0 = new Point32();
        public final Point32 dir1 = new Point32();

        public Face() {
            construct();
        }

        @Override
        public void construct() {
            next = null;
            nearbyVertex = null;
            nextWithSameNearbyVertex = null;
        }

        @Override
        public Face poolNext() {
            return next;
        }

        @Override
        public void setPoolNext(Face n) {
            next = n;
        }

        public void init(Vertex a, Vertex b, Vertex c) {
            nearbyVertex = a;
            origin.set(a.point);
            dir0.set(b.sub(a));
            dir1.set(c.sub(a));
            if (a.lastNearbyFace != null) {
                a.lastNearbyFace.nextWithSameNearbyVertex = this;
            } else {
                a.firstNearbyFace = this;
            }
            a.lastNearbyFace = this;
        }

        public Point64 getNormal() {
            return dir0.cross(dir1);
        }
    }

    /** {@code template<UWord, UHWord> class DMul}: both instantiations used by the file. */
    public static final class DMul {
        private DMul() {}

        /** {@code DMul<uint64_t, uint32_t>::mul(a, b, resLow, resHigh)}; out = {low, high}. */
        public static void mul64(long a, long b, long[] out) {
            long p00 = (a & 0xffffffffL) * (b & 0xffffffffL);
            long p01 = (a & 0xffffffffL) * (b >>> 32);
            long p10 = (a >>> 32) * (b & 0xffffffffL);
            long p11 = (a >>> 32) * (b >>> 32);
            long p0110 = (p01 & 0xffffffffL) + (p10 & 0xffffffffL);
            p11 += p01 >>> 32;
            p11 += p10 >>> 32;
            p11 += p0110 >>> 32;
            p0110 <<= 32;
            p00 += p0110;
            if (Long.compareUnsigned(p00, p0110) < 0) {
                ++p11;
            }
            out[0] = p00;
            out[1] = p11;
        }

        /** {@code DMul<Int128, uint64_t>::mul(a, b, resLow, resHigh)}. */
        public static void mul128(Int128 a, Int128 b, Int128 resLow, Int128 resHigh) {
            Int128 p00 = Int128.mulUnsigned(a.low, b.low);
            Int128 p01 = Int128.mulUnsigned(a.low, b.high);
            Int128 p10 = Int128.mulUnsigned(a.high, b.low);
            Int128 p11 = Int128.mulUnsigned(a.high, b.high);
            Int128 p0110 = Int128.fromUnsigned(p01.low).add(Int128.fromUnsigned(p10.low));
            p11.addLocal(Int128.fromUnsigned(p01.high));
            p11.addLocal(Int128.fromUnsigned(p10.high));
            p11.addLocal(Int128.fromUnsigned(p0110.high));
            // shlHalf(p0110)
            p0110.high = p0110.low;
            p0110.low = 0;
            p00.addLocal(p0110);
            if (p00.lessThan(p0110)) {
                p11.increment();
            }
            resLow.set(p00);
            resHigh.set(p11);
        }
    }

    public static class IntermediateHull {
        public Vertex minXy;
        public Vertex maxXy;
        public Vertex minYx;
        public Vertex maxYx;

        public IntermediateHull() {}

        public IntermediateHull set(IntermediateHull o) {
            minXy = o.minXy;
            maxXy = o.maxXy;
            minYx = o.minYx;
            maxYx = o.maxYx;
            return this;
        }
    }

    // enum Orientation
    public static final int NONE = 0;
    public static final int CLOCKWISE = 1;
    public static final int COUNTER_CLOCKWISE = 2;

    public static class PoolArray<T extends Poolable<T>> {
        final Object[] array;
        final int size;
        final long arrayAddr;
        public PoolArray<T> next;

        PoolArray(int size, java.util.function.Supplier<T> factory) {
            this.size = size;
            this.next = null;
            this.arrayAddr = btGlobals.btAlignedAlloc(64L * size, 16);
            array = new Object[size];
            for (int i = 0; i < size; i++) {
                array[i] = factory.get();
            }
        }

        void destruct() {
            btGlobals.btAlignedFree(arrayAddr);
        }

        @SuppressWarnings("unchecked")
        T init() {
            for (int i = 0; i < size; i++) {
                ((T) array[i]).setPoolNext((i + 1 < size) ? (T) array[i + 1] : null);
            }
            return size > 0 ? (T) array[0] : null;
        }
    }

    public static class Pool<T extends Poolable<T>> {
        private PoolArray<T> arrays;
        private PoolArray<T> nextArray;
        private T freeObjects;
        private int arraySize;
        private final java.util.function.Supplier<T> factory;

        public Pool(java.util.function.Supplier<T> factory) {
            this.factory = factory;
            arrays = null;
            nextArray = null;
            freeObjects = null;
            arraySize = 256;
        }

        /** ~Pool() */
        public void destruct() {
            while (arrays != null) {
                PoolArray<T> p = arrays;
                arrays = p.next;
                p.destruct();
                btGlobals.btAlignedFree(1); // btAlignedFree(p)
            }
        }

        public void reset() {
            nextArray = arrays;
            freeObjects = null;
        }

        public void setArraySize(int arraySize) {
            this.arraySize = arraySize;
        }

        public T newObject() {
            T o = freeObjects;
            if (o == null) {
                PoolArray<T> p = nextArray;
                if (p != null) {
                    nextArray = p.next;
                } else {
                    btGlobals.btAlignedAlloc(32, 16); // sizeof(PoolArray<T>)
                    p = new PoolArray<>(arraySize, factory);
                    p.next = arrays;
                    arrays = p;
                }
                o = p.init();
            }
            freeObjects = o.poolNext();
            o.construct();
            return o;
        }

        public void freeObject(T object) {
            if (object instanceof Edge) {
                ((Edge) object).destruct();
            }
            object.setPoolNext(freeObjects);
            freeObjects = object;
        }
    }

    // ---------------------------------------------------------------------------------------
    // members
    // ---------------------------------------------------------------------------------------
    public final btVector3 scaling = new btVector3();
    public final btVector3 center = new btVector3();
    public final Pool<Vertex> vertexPool = new Pool<>(Vertex::new);
    public final Pool<Edge> edgePool = new Pool<>(Edge::new);
    public final Pool<Face> facePool = new Pool<>(Face::new);
    public final btAlignedObjectArray<Vertex> originalVertices = new btAlignedObjectArray<>();
    public int mergeStamp;
    public int minAxis;
    public int medAxis;
    public int maxAxis;
    public int usedEdgePairs;
    public int maxUsedEdgePairs;

    public Vertex vertexList;

    /**
     * ~btConvexHullInternal(): members in reverse declaration order (originalVertices, then pools).
     */
    public void destruct() {
        originalVertices.clear();
        facePool.destruct();
        edgePool.destruct();
        vertexPool.destruct();
    }

    static int getOrientation(Edge prev, Edge next, Point32 s, Point32 t) {
        if (prev.next == next) {
            if (prev.prev == next) {
                Point64 n = t.cross(s);
                Point64 m =
                        (prev.target.sub(next.reverse.target))
                                .cross(next.target.sub(next.reverse.target));
                long dot = n.dot(m);
                return (dot > 0) ? COUNTER_CLOCKWISE : CLOCKWISE;
            }
            return COUNTER_CLOCKWISE;
        } else if (prev.prev == next) {
            return CLOCKWISE;
        } else {
            return NONE;
        }
    }

    Edge findMaxAngle(
            boolean ccw, Vertex start, Point32 s, Point64 rxs, Point64 sxrxs, Rational64 minCot) {
        Edge minEdge = null;

        Edge e = start.edges;
        if (e != null) {
            do {
                if (e.copy > mergeStamp) {
                    Point32 t = e.target.sub(start);
                    Rational64 cot = new Rational64(t.dot(sxrxs), t.dot(rxs));
                    if (cot.isNaN()) {
                        // btAssert only
                    } else {
                        int cmp;
                        if (minEdge == null) {
                            minCot.set(cot);
                            minEdge = e;
                        } else if ((cmp = cot.compare(minCot)) < 0) {
                            minCot.set(cot);
                            minEdge = e;
                        } else if ((cmp == 0)
                                && (ccw
                                        == (getOrientation(minEdge, e, s, t)
                                                == COUNTER_CLOCKWISE))) {
                            minEdge = e;
                        }
                    }
                }
                e = e.next;
            } while (e != start.edges);
        }
        return minEdge;
    }

    /** {@code Edge*& e0, Edge*& e1} are {@code Edge[1]} holders. */
    void findEdgeForCoplanarFaces(
            Vertex c0, Vertex c1, Edge[] e0, Edge[] e1, Vertex stop0, Vertex stop1) {
        Edge start0 = e0[0];
        Edge start1 = e1[0];
        Point32 et0 = new Point32(start0 != null ? start0.target.point : c0.point);
        Point32 et1 = new Point32(start1 != null ? start1.target.point : c1.point);
        Point32 s = c1.point.sub(c0.point);
        Point64 normal = ((start0 != null ? start0 : start1).target.point.sub(c0.point)).cross(s);
        long dist = c0.point.dot(normal);
        Point64 perp = s.cross(normal);

        long maxDot0 = et0.dot(perp);
        if (e0[0] != null) {
            while (e0[0].target != stop0) {
                Edge e = e0[0].reverse.prev;
                if (e.target.point.dot(normal) < dist) {
                    break;
                }
                if (e.copy == mergeStamp) {
                    break;
                }
                long dot = e.target.point.dot(perp);
                if (dot <= maxDot0) {
                    break;
                }
                maxDot0 = dot;
                e0[0] = e;
                et0.set(e.target.point);
            }
        }

        long maxDot1 = et1.dot(perp);
        if (e1[0] != null) {
            while (e1[0].target != stop1) {
                Edge e = e1[0].reverse.next;
                if (e.target.point.dot(normal) < dist) {
                    break;
                }
                if (e.copy == mergeStamp) {
                    break;
                }
                long dot = e.target.point.dot(perp);
                if (dot <= maxDot1) {
                    break;
                }
                maxDot1 = dot;
                e1[0] = e;
                et1.set(e.target.point);
            }
        }

        long dx = maxDot1 - maxDot0;
        if (dx > 0) {
            while (true) {
                long dy = (et1.sub(et0)).dot(s);

                if (e0[0] != null && (e0[0].target != stop0)) {
                    Edge f0 = e0[0].next.reverse;
                    if (f0.copy > mergeStamp) {
                        long dx0 = (f0.target.point.sub(et0)).dot(perp);
                        long dy0 = (f0.target.point.sub(et0)).dot(s);
                        if ((dx0 == 0)
                                ? (dy0 < 0)
                                : ((dx0 < 0)
                                        && (new Rational64(dy0, dx0).compare(new Rational64(dy, dx))
                                                >= 0))) {
                            et0.set(f0.target.point);
                            dx = (et1.sub(et0)).dot(perp);
                            e0[0] = (e0[0] == start0) ? null : f0;
                            continue;
                        }
                    }
                }

                if (e1[0] != null && (e1[0].target != stop1)) {
                    Edge f1 = e1[0].reverse.next;
                    if (f1.copy > mergeStamp) {
                        Point32 d1 = f1.target.point.sub(et1);
                        if (d1.dot(normal) == 0) {
                            long dx1 = d1.dot(perp);
                            long dy1 = d1.dot(s);
                            long dxn = (f1.target.point.sub(et0)).dot(perp);
                            if ((dxn > 0)
                                    && ((dx1 == 0)
                                            ? (dy1 < 0)
                                            : ((dx1 < 0)
                                                    && (new Rational64(dy1, dx1)
                                                                    .compare(new Rational64(dy, dx))
                                                            > 0)))) {
                                e1[0] = f1;
                                et1.set(e1[0].target.point);
                                dx = dxn;
                                continue;
                            }
                        }
                    }
                }
                break;
            }
        } else if (dx < 0) {
            while (true) {
                long dy = (et1.sub(et0)).dot(s);

                if (e1[0] != null && (e1[0].target != stop1)) {
                    Edge f1 = e1[0].prev.reverse;
                    if (f1.copy > mergeStamp) {
                        long dx1 = (f1.target.point.sub(et1)).dot(perp);
                        long dy1 = (f1.target.point.sub(et1)).dot(s);
                        if ((dx1 == 0)
                                ? (dy1 > 0)
                                : ((dx1 < 0)
                                        && (new Rational64(dy1, dx1).compare(new Rational64(dy, dx))
                                                <= 0))) {
                            et1.set(f1.target.point);
                            dx = (et1.sub(et0)).dot(perp);
                            e1[0] = (e1[0] == start1) ? null : f1;
                            continue;
                        }
                    }
                }

                if (e0[0] != null && (e0[0].target != stop0)) {
                    Edge f0 = e0[0].reverse.prev;
                    if (f0.copy > mergeStamp) {
                        Point32 d0 = f0.target.point.sub(et0);
                        if (d0.dot(normal) == 0) {
                            long dx0 = d0.dot(perp);
                            long dy0 = d0.dot(s);
                            long dxn = (et1.sub(f0.target.point)).dot(perp);
                            if ((dxn < 0)
                                    && ((dx0 == 0)
                                            ? (dy0 > 0)
                                            : ((dx0 < 0)
                                                    && (new Rational64(dy0, dx0)
                                                                    .compare(new Rational64(dy, dx))
                                                            < 0)))) {
                                e0[0] = f0;
                                et0.set(e0[0].target.point);
                                dx = dxn;
                                continue;
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    Edge newEdgePair(Vertex from, Vertex to) {
        Edge e = edgePool.newObject();
        Edge r = edgePool.newObject();
        e.reverse = r;
        r.reverse = e;
        e.copy = mergeStamp;
        r.copy = mergeStamp;
        e.target = to;
        r.target = from;
        e.face = null;
        r.face = null;
        usedEdgePairs++;
        if (usedEdgePairs > maxUsedEdgePairs) {
            maxUsedEdgePairs = usedEdgePairs;
        }
        return e;
    }

    void removeEdgePair(Edge edge) {
        Edge n = edge.next;
        Edge r = edge.reverse;

        if (n != edge) {
            n.prev = edge.prev;
            edge.prev.next = n;
            r.target.edges = n;
        } else {
            r.target.edges = null;
        }

        n = r.next;

        if (n != r) {
            n.prev = r.prev;
            r.prev.next = n;
            edge.target.edges = n;
        } else {
            edge.target.edges = null;
        }

        edgePool.freeObject(edge);
        edgePool.freeObject(r);
        usedEdgePairs--;
    }

    /** {@code Vertex*& c0, Vertex*& c1} are {@code Vertex[1]} holders. */
    boolean mergeProjection(IntermediateHull h0, IntermediateHull h1, Vertex[] c0, Vertex[] c1) {
        Vertex v0 = h0.maxYx;
        Vertex v1 = h1.minYx;
        if ((v0.point.x == v1.point.x) && (v0.point.y == v1.point.y)) {
            Vertex v1p = v1.prev;
            if (v1p == v1) {
                c0[0] = v0;
                if (v1.edges != null) {
                    v1 = v1.edges.target;
                }
                c1[0] = v1;
                return false;
            }
            Vertex v1n = v1.next;
            v1p.next = v1n;
            v1n.prev = v1p;
            if (v1 == h1.minXy) {
                if ((v1n.point.x < v1p.point.x)
                        || ((v1n.point.x == v1p.point.x) && (v1n.point.y < v1p.point.y))) {
                    h1.minXy = v1n;
                } else {
                    h1.minXy = v1p;
                }
            }
            if (v1 == h1.maxXy) {
                if ((v1n.point.x > v1p.point.x)
                        || ((v1n.point.x == v1p.point.x) && (v1n.point.y > v1p.point.y))) {
                    h1.maxXy = v1n;
                } else {
                    h1.maxXy = v1p;
                }
            }
        }

        v0 = h0.maxXy;
        v1 = h1.maxXy;
        Vertex v00 = null;
        Vertex v10 = null;
        int sign = 1;

        for (int side = 0; side <= 1; side++) {
            int dx = (v1.point.x - v0.point.x) * sign;
            if (dx > 0) {
                while (true) {
                    int dy = v1.point.y - v0.point.y;

                    Vertex w0 = side != 0 ? v0.next : v0.prev;
                    if (w0 != v0) {
                        int dx0 = (w0.point.x - v0.point.x) * sign;
                        int dy0 = w0.point.y - v0.point.y;
                        if ((dy0 <= 0) && ((dx0 == 0) || ((dx0 < 0) && (dy0 * dx <= dy * dx0)))) {
                            v0 = w0;
                            dx = (v1.point.x - v0.point.x) * sign;
                            continue;
                        }
                    }

                    Vertex w1 = side != 0 ? v1.next : v1.prev;
                    if (w1 != v1) {
                        int dx1 = (w1.point.x - v1.point.x) * sign;
                        int dy1 = w1.point.y - v1.point.y;
                        int dxn = (w1.point.x - v0.point.x) * sign;
                        if ((dxn > 0)
                                && (dy1 < 0)
                                && ((dx1 == 0) || ((dx1 < 0) && (dy1 * dx < dy * dx1)))) {
                            v1 = w1;
                            dx = dxn;
                            continue;
                        }
                    }

                    break;
                }
            } else if (dx < 0) {
                while (true) {
                    int dy = v1.point.y - v0.point.y;

                    Vertex w1 = side != 0 ? v1.prev : v1.next;
                    if (w1 != v1) {
                        int dx1 = (w1.point.x - v1.point.x) * sign;
                        int dy1 = w1.point.y - v1.point.y;
                        if ((dy1 >= 0) && ((dx1 == 0) || ((dx1 < 0) && (dy1 * dx <= dy * dx1)))) {
                            v1 = w1;
                            dx = (v1.point.x - v0.point.x) * sign;
                            continue;
                        }
                    }

                    Vertex w0 = side != 0 ? v0.prev : v0.next;
                    if (w0 != v0) {
                        int dx0 = (w0.point.x - v0.point.x) * sign;
                        int dy0 = w0.point.y - v0.point.y;
                        int dxn = (v1.point.x - w0.point.x) * sign;
                        if ((dxn < 0)
                                && (dy0 > 0)
                                && ((dx0 == 0) || ((dx0 < 0) && (dy0 * dx < dy * dx0)))) {
                            v0 = w0;
                            dx = dxn;
                            continue;
                        }
                    }

                    break;
                }
            } else {
                int x = v0.point.x;
                int y0 = v0.point.y;
                Vertex w0 = v0;
                Vertex t;
                while (((t = side != 0 ? w0.next : w0.prev) != v0)
                        && (t.point.x == x)
                        && (t.point.y <= y0)) {
                    w0 = t;
                    y0 = t.point.y;
                }
                v0 = w0;

                int y1 = v1.point.y;
                Vertex w1 = v1;
                while (((t = side != 0 ? w1.prev : w1.next) != v1)
                        && (t.point.x == x)
                        && (t.point.y >= y1)) {
                    w1 = t;
                    y1 = t.point.y;
                }
                v1 = w1;
            }

            if (side == 0) {
                v00 = v0;
                v10 = v1;

                v0 = h0.minXy;
                v1 = h1.minXy;
                sign = -1;
            }
        }

        v0.prev = v1;
        v1.next = v0;

        v00.next = v10;
        v10.prev = v00;

        if (h1.minXy.point.x < h0.minXy.point.x) {
            h0.minXy = h1.minXy;
        }
        if (h1.maxXy.point.x >= h0.maxXy.point.x) {
            h0.maxXy = h1.maxXy;
        }

        h0.maxYx = h1.maxYx;

        c0[0] = v00;
        c1[0] = v10;

        return true;
    }

    void computeInternal(int start, int end, IntermediateHull result) {
        int n = end - start;
        switch (n) {
            case 0:
                result.minXy = null;
                result.maxXy = null;
                result.minYx = null;
                result.maxYx = null;
                return;
            case 2:
                {
                    Vertex v = originalVertices.get(start);
                    // Vertex* w = v + 1: vertices are contiguous in one PoolArray.
                    Vertex w = originalVertices.get(start + 1);
                    if (v.point.notEquals(w.point)) {
                        int dx = v.point.x - w.point.x;
                        int dy = v.point.y - w.point.y;

                        if ((dx == 0) && (dy == 0)) {
                            if (v.point.z > w.point.z) {
                                Vertex t = w;
                                w = v;
                                v = t;
                            }
                            v.next = v;
                            v.prev = v;
                            result.minXy = v;
                            result.maxXy = v;
                            result.minYx = v;
                            result.maxYx = v;
                        } else {
                            v.next = w;
                            v.prev = w;
                            w.next = v;
                            w.prev = v;

                            if ((dx < 0) || ((dx == 0) && (dy < 0))) {
                                result.minXy = v;
                                result.maxXy = w;
                            } else {
                                result.minXy = w;
                                result.maxXy = v;
                            }

                            if ((dy < 0) || ((dy == 0) && (dx < 0))) {
                                result.minYx = v;
                                result.maxYx = w;
                            } else {
                                result.minYx = w;
                                result.maxYx = v;
                            }
                        }

                        Edge e = newEdgePair(v, w);
                        e.link(e);
                        v.edges = e;

                        e = e.reverse;
                        e.link(e);
                        w.edges = e;

                        return;
                    }
                }
            // fall through
            case 1:
                {
                    Vertex v = originalVertices.get(start);
                    v.edges = null;
                    v.next = v;
                    v.prev = v;

                    result.minXy = v;
                    result.maxXy = v;
                    result.minYx = v;
                    result.maxYx = v;

                    return;
                }
            default:
                break;
        }

        int split0 = start + n / 2;
        Point32 p = new Point32(originalVertices.get(split0 - 1).point);
        int split1 = split0;
        while ((split1 < end) && (originalVertices.get(split1).point.equalsValue(p))) {
            split1++;
        }
        computeInternal(start, split0, result);
        IntermediateHull hull1 = new IntermediateHull();
        computeInternal(split1, end, hull1);
        merge(result, hull1);
    }

    void merge(IntermediateHull h0, IntermediateHull h1) {
        if (h1.maxXy == null) {
            return;
        }
        if (h0.maxXy == null) {
            h0.set(h1);
            return;
        }

        mergeStamp--;

        Vertex[] c0 = {null};
        Edge toPrev0 = null;
        Edge firstNew0 = null;
        Edge pendingHead0 = null;
        Edge pendingTail0 = null;
        Vertex[] c1 = {null};
        Edge toPrev1 = null;
        Edge firstNew1 = null;
        Edge pendingHead1 = null;
        Edge pendingTail1 = null;
        Point32 prevPoint = new Point32();

        if (mergeProjection(h0, h1, c0, c1)) {
            Point32 s = c1[0].sub(c0[0]);
            Point64 normal = new Point32(0, 0, -1).cross(s);
            Point64 t = s.cross(normal);

            Edge e = c0[0].edges;
            Edge start0 = null;
            if (e != null) {
                do {
                    long dot = (e.target.sub(c0[0])).dot(normal);
                    if ((dot == 0) && ((e.target.sub(c0[0])).dot(t) > 0)) {
                        if (start0 == null
                                || (getOrientation(start0, e, s, new Point32(0, 0, -1))
                                        == CLOCKWISE)) {
                            start0 = e;
                        }
                    }
                    e = e.next;
                } while (e != c0[0].edges);
            }

            e = c1[0].edges;
            Edge start1 = null;
            if (e != null) {
                do {
                    long dot = (e.target.sub(c1[0])).dot(normal);
                    if ((dot == 0) && ((e.target.sub(c1[0])).dot(t) > 0)) {
                        if (start1 == null
                                || (getOrientation(start1, e, s, new Point32(0, 0, -1))
                                        == COUNTER_CLOCKWISE)) {
                            start1 = e;
                        }
                    }
                    e = e.next;
                } while (e != c1[0].edges);
            }

            if (start0 != null || start1 != null) {
                Edge[] s0 = {start0};
                Edge[] s1 = {start1};
                findEdgeForCoplanarFaces(c0[0], c1[0], s0, s1, null, null);
                start0 = s0[0];
                start1 = s1[0];
                if (start0 != null) {
                    c0[0] = start0.target;
                }
                if (start1 != null) {
                    c1[0] = start1.target;
                }
            }

            prevPoint.set(c1[0].point);
            prevPoint.z++;
        } else {
            prevPoint.set(c1[0].point);
            prevPoint.x++;
        }

        Vertex first0 = c0[0];
        Vertex first1 = c1[0];
        boolean firstRun = true;

        while (true) {
            Point32 s = c1[0].sub(c0[0]);
            Point32 r = prevPoint.sub(c0[0].point);
            Point64 rxs = r.cross(s);
            Point64 sxrxs = s.cross(rxs);

            Rational64 minCot0 = new Rational64(0, 0);
            Edge min0 = findMaxAngle(false, c0[0], s, rxs, sxrxs, minCot0);
            Rational64 minCot1 = new Rational64(0, 0);
            Edge min1 = findMaxAngle(true, c1[0], s, rxs, sxrxs, minCot1);
            if (min0 == null && min1 == null) {
                Edge e = newEdgePair(c0[0], c1[0]);
                e.link(e);
                c0[0].edges = e;

                e = e.reverse;
                e.link(e);
                c1[0].edges = e;
                return;
            } else {
                int cmp = min0 == null ? 1 : min1 == null ? -1 : minCot0.compare(minCot1);
                if (firstRun
                        || ((cmp >= 0)
                                ? !minCot1.isNegativeInfinity()
                                : !minCot0.isNegativeInfinity())) {
                    Edge e = newEdgePair(c0[0], c1[0]);
                    if (pendingTail0 != null) {
                        pendingTail0.prev = e;
                    } else {
                        pendingHead0 = e;
                    }
                    e.next = pendingTail0;
                    pendingTail0 = e;

                    e = e.reverse;
                    if (pendingTail1 != null) {
                        pendingTail1.next = e;
                    } else {
                        pendingHead1 = e;
                    }
                    e.prev = pendingTail1;
                    pendingTail1 = e;
                }

                Edge[] e0 = {min0};
                Edge[] e1 = {min1};

                if (cmp == 0) {
                    findEdgeForCoplanarFaces(c0[0], c1[0], e0, e1, null, null);
                }

                if ((cmp >= 0) && e1[0] != null) {
                    if (toPrev1 != null) {
                        for (Edge e = toPrev1.next, n = null; e != min1; e = n) {
                            n = e.next;
                            removeEdgePair(e);
                        }
                    }

                    if (pendingTail1 != null) {
                        if (toPrev1 != null) {
                            toPrev1.link(pendingHead1);
                        } else {
                            min1.prev.link(pendingHead1);
                            firstNew1 = pendingHead1;
                        }
                        pendingTail1.link(min1);
                        pendingHead1 = null;
                        pendingTail1 = null;
                    } else if (toPrev1 == null) {
                        firstNew1 = min1;
                    }

                    prevPoint.set(c1[0].point);
                    c1[0] = e1[0].target;
                    toPrev1 = e1[0].reverse;
                }

                if ((cmp <= 0) && e0[0] != null) {
                    if (toPrev0 != null) {
                        for (Edge e = toPrev0.prev, n = null; e != min0; e = n) {
                            n = e.prev;
                            removeEdgePair(e);
                        }
                    }

                    if (pendingTail0 != null) {
                        if (toPrev0 != null) {
                            pendingHead0.link(toPrev0);
                        } else {
                            pendingHead0.link(min0.next);
                            firstNew0 = pendingHead0;
                        }
                        min0.link(pendingTail0);
                        pendingHead0 = null;
                        pendingTail0 = null;
                    } else if (toPrev0 == null) {
                        firstNew0 = min0;
                    }

                    prevPoint.set(c0[0].point);
                    c0[0] = e0[0].target;
                    toPrev0 = e0[0].reverse;
                }
            }

            if ((c0[0] == first0) && (c1[0] == first1)) {
                if (toPrev0 == null) {
                    pendingHead0.link(pendingTail0);
                    c0[0].edges = pendingTail0;
                } else {
                    for (Edge e = toPrev0.prev, n = null; e != firstNew0; e = n) {
                        n = e.prev;
                        removeEdgePair(e);
                    }
                    if (pendingTail0 != null) {
                        pendingHead0.link(toPrev0);
                        firstNew0.link(pendingTail0);
                    }
                }

                if (toPrev1 == null) {
                    pendingTail1.link(pendingHead1);
                    c1[0].edges = pendingTail1;
                } else {
                    for (Edge e = toPrev1.next, n = null; e != firstNew1; e = n) {
                        n = e.next;
                        removeEdgePair(e);
                    }
                    if (pendingTail1 != null) {
                        toPrev1.link(pendingHead1);
                        pendingTail1.link(firstNew1);
                    }
                }

                return;
            }

            firstRun = false;
        }
    }

    /** {@code class pointCmp}: y, then x, then z. */
    public static boolean pointCmp(Point32 p, Point32 q) {
        return (p.y < q.y) || ((p.y == q.y) && ((p.x < q.x) || ((p.x == q.x) && (p.z < q.z))));
    }

    /** Source of input coordinates ({@code const void* coords} + stride). */
    public interface CoordSource {
        /** Component k (0..2) of point i, already converted to btScalar. */
        double get(int i, int k);
    }

    /**
     * {@code void compute(const void* coords, bool doubleCoords, int stride, int count)}. Both C++
     * branches convert each component to btScalar the same way, so one reader suffices.
     */
    public void compute(CoordSource coords, int count) {
        btVector3 min = new btVector3(1e30, 1e30, 1e30), max = new btVector3(-1e30, -1e30, -1e30);
        for (int i = 0; i < count; i++) {
            btVector3 p = new btVector3(coords.get(i, 0), coords.get(i, 1), coords.get(i, 2));
            min.setMin(p);
            max.setMax(p);
        }

        btVector3 s = max.sub(min);
        maxAxis = s.maxAxis();
        minAxis = s.minAxis();
        if (minAxis == maxAxis) {
            minAxis = (maxAxis + 1) % 3;
        }
        medAxis = 3 - maxAxis - minAxis;

        s.divLocal(10216.0);
        if (((medAxis + 1) % 3) != maxAxis) {
            s.mulLocal(-1.0);
        }
        scaling.set(s);

        if (s.get(0) != 0) {
            s.set(0, 1.0 / s.get(0));
        }
        if (s.get(1) != 0) {
            s.set(1, 1.0 / s.get(1));
        }
        if (s.get(2) != 0) {
            s.set(2, 1.0 / s.get(2));
        }

        center.set((min.add(max)).mul(0.5));

        btAlignedObjectArray<Point32> points =
                new btAlignedObjectArray<>(Point32::new, Point32::set);
        points.resize(count);
        for (int i = 0; i < count; i++) {
            btVector3 p = new btVector3(coords.get(i, 0), coords.get(i, 1), coords.get(i, 2));
            p = (p.sub(center)).mul(s);
            Point32 pi = points.get(i);
            pi.x = btScalar.cvttsd2si(p.get(medAxis));
            pi.y = btScalar.cvttsd2si(p.get(maxAxis));
            pi.z = btScalar.cvttsd2si(p.get(minAxis));
            pi.index = i;
        }
        points.quickSort(btConvexHullInternal::pointCmp);

        vertexPool.reset();
        vertexPool.setArraySize(count);
        originalVertices.resize(count);
        for (int i = 0; i < count; i++) {
            Vertex v = vertexPool.newObject();
            v.edges = null;
            v.point.set(points.get(i));
            v.copy = -1;
            originalVertices.set(i, v);
        }

        points.clear();

        edgePool.reset();
        edgePool.setArraySize(6 * count);

        usedEdgePairs = 0;
        maxUsedEdgePairs = 0;

        mergeStamp = -3;

        IntermediateHull hull = new IntermediateHull();
        computeInternal(0, count, hull);
        vertexList = hull.minXy;
    }

    btVector3 toBtVector(Point32 v) {
        btVector3 p = new btVector3();
        p.set(medAxis, (double) v.x);
        p.set(maxAxis, (double) v.y);
        p.set(minAxis, (double) v.z);
        return p.mul(scaling);
    }

    btVector3 getBtNormal(Face face) {
        return toBtVector(face.dir0).cross(toBtVector(face.dir1)).normalized();
    }

    public btVector3 getCoordinates(Vertex v) {
        btVector3 p = new btVector3();
        p.set(medAxis, v.xvalue());
        p.set(maxAxis, v.yvalue());
        p.set(minAxis, v.zvalue());
        return p.mul(scaling).add(center);
    }

    public double shrink(double amount, double clampAmount) {
        if (vertexList == null) {
            return 0;
        }
        int stamp = --mergeStamp;
        btAlignedObjectArray<Vertex> stack = new btAlignedObjectArray<>();
        vertexList.copy = stamp;
        stack.push_back(vertexList);
        btAlignedObjectArray<Face> faces = new btAlignedObjectArray<>();

        Point32 ref = new Point32(vertexList.point);
        Int128 hullCenterX = new Int128(0, 0);
        Int128 hullCenterY = new Int128(0, 0);
        Int128 hullCenterZ = new Int128(0, 0);
        Int128 volume = new Int128(0, 0);

        while (stack.size() > 0) {
            Vertex v = stack.get(stack.size() - 1);
            stack.pop_back();
            Edge e = v.edges;
            if (e != null) {
                do {
                    if (e.target.copy != stamp) {
                        e.target.copy = stamp;
                        stack.push_back(e.target);
                    }
                    if (e.copy != stamp) {
                        Face face = facePool.newObject();
                        face.init(e.target, e.reverse.prev.target, v);
                        faces.push_back(face);
                        Edge f = e;

                        Vertex a = null;
                        Vertex b = null;
                        do {
                            if (a != null && b != null) {
                                long vol =
                                        (v.point.sub(ref))
                                                .dot((a.point.sub(ref)).cross(b.point.sub(ref)));
                                Point32 c = v.point.add(a.point).add(b.point).add(ref);
                                hullCenterX.addLocal(Int128.fromSigned(vol * c.x));
                                hullCenterY.addLocal(Int128.fromSigned(vol * c.y));
                                hullCenterZ.addLocal(Int128.fromSigned(vol * c.z));
                                volume.addLocal(Int128.fromSigned(vol));
                            }

                            f.copy = stamp;
                            f.face = face;

                            a = b;
                            b = f.target;

                            f = f.reverse.prev;
                        } while (f != e);
                    }
                    e = e.next;
                } while (e != v.edges);
            }
        }

        if (volume.getSign() <= 0) {
            return 0;
        }

        btVector3 hullCenter = new btVector3();
        hullCenter.set(medAxis, hullCenterX.toScalar());
        hullCenter.set(maxAxis, hullCenterY.toScalar());
        hullCenter.set(minAxis, hullCenterZ.toScalar());
        hullCenter.divLocal(4 * volume.toScalar());
        hullCenter.mulLocal(scaling);

        int faceCount = faces.size();

        if (clampAmount > 0) {
            double minDist = btScalar.SIMD_INFINITY;
            for (int i = 0; i < faceCount; i++) {
                btVector3 normal = getBtNormal(faces.get(i));
                double dist = normal.dot(toBtVector(faces.get(i).origin).sub(hullCenter));
                if (dist < minDist) {
                    minDist = dist;
                }
            }

            if (minDist <= 0) {
                return 0;
            }

            amount = btMinMax.btMin(amount, minDist * clampAmount);
        }

        int seed = 243703;
        for (int i = 0; i < faceCount; i++, seed = 1664525 * seed + 1013904223) {
            // btSwap(faces[i], faces[seed % faceCount]) -- unsigned modulo
            int j = Integer.remainderUnsigned(seed, faceCount);
            Face tmp = faces.get(i);
            faces.set(i, faces.get(j));
            faces.set(j, tmp);
        }

        for (int i = 0; i < faceCount; i++) {
            // stack is passed by value (copy constructor).
            if (!shiftFace(faces.get(i), amount, new btAlignedObjectArray<>(stack))) {
                return -amount;
            }
        }

        return amount;
    }

    /** {@code stack} is the by-value copy made at the call site. */
    boolean shiftFace(Face face, double amount, btAlignedObjectArray<Vertex> stack) {
        btVector3 origShift = getBtNormal(face).mul(-amount);
        if (scaling.get(0) != 0) {
            origShift.set(0, origShift.get(0) / scaling.get(0));
        }
        if (scaling.get(1) != 0) {
            origShift.set(1, origShift.get(1) / scaling.get(1));
        }
        if (scaling.get(2) != 0) {
            origShift.set(2, origShift.get(2) / scaling.get(2));
        }
        Point32 shift =
                new Point32(
                        btScalar.cvttsd2si(origShift.get(medAxis)),
                        btScalar.cvttsd2si(origShift.get(maxAxis)),
                        btScalar.cvttsd2si(origShift.get(minAxis)));
        if (shift.isZero()) {
            return true;
        }
        Point64 normal = face.getNormal();
        long origDot = face.origin.dot(normal);
        Point32 shiftedOrigin = face.origin.add(shift);
        long shiftedDot = shiftedOrigin.dot(normal);
        if (shiftedDot >= origDot) {
            return false;
        }

        Edge intersection = null;

        Edge startEdge = face.nearbyVertex.edges;
        Rational128 optDot = face.nearbyVertex.dot(normal);
        int cmp = optDot.compare(shiftedDot);
        if (cmp >= 0) {
            Edge e = startEdge;
            do {
                Rational128 dot = e.target.dot(normal);
                if (dot.compare(optDot) < 0) {
                    int c = dot.compare(shiftedDot);
                    optDot.set(dot);
                    e = e.reverse;
                    startEdge = e;
                    if (c < 0) {
                        intersection = e;
                        break;
                    }
                    cmp = c;
                }
                e = e.prev;
            } while (e != startEdge);

            if (intersection == null) {
                return false;
            }
        } else {
            Edge e = startEdge;
            do {
                Rational128 dot = e.target.dot(normal);
                if (dot.compare(optDot) > 0) {
                    cmp = dot.compare(shiftedDot);
                    if (cmp >= 0) {
                        intersection = e;
                        break;
                    }
                    optDot.set(dot);
                    e = e.reverse;
                    startEdge = e;
                }
                e = e.prev;
            } while (e != startEdge);

            if (intersection == null) {
                return true;
            }
        }

        if (cmp == 0) {
            Edge e = intersection.reverse.next;
            while (e.target.dot(normal).compare(shiftedDot) <= 0) {
                e = e.next;
                if (e == intersection.reverse) {
                    return true;
                }
            }
        }

        Edge firstIntersection = null;
        Edge faceEdge = null;
        Edge firstFaceEdge = null;

        while (true) {
            if (cmp == 0) {
                Edge e = intersection.reverse.next;
                startEdge = e;
                while (true) {
                    if (e.target.dot(normal).compare(shiftedDot) >= 0) {
                        break;
                    }
                    intersection = e.reverse;
                    e = e.next;
                    if (e == startEdge) {
                        return true;
                    }
                }
            }

            if (firstIntersection == null) {
                firstIntersection = intersection;
            } else if (intersection == firstIntersection) {
                break;
            }

            int prevCmp = cmp;
            Edge prevIntersection = intersection;
            Edge prevFaceEdge = faceEdge;

            Edge e = intersection.reverse;
            while (true) {
                e = e.reverse.prev;
                cmp = e.target.dot(normal).compare(shiftedDot);
                if (cmp >= 0) {
                    intersection = e;
                    break;
                }
            }

            if (cmp > 0) {
                Vertex removed = intersection.target;
                e = intersection.reverse;
                if (e.prev == e) {
                    removed.edges = null;
                } else {
                    removed.edges = e.prev;
                    e.prev.link(e.next);
                    e.link(e);
                }

                Point64 n0 = intersection.face.getNormal();
                Point64 n1 = intersection.reverse.face.getNormal();
                long m00 = face.dir0.dot(n0);
                long m01 = face.dir1.dot(n0);
                long m10 = face.dir0.dot(n1);
                long m11 = face.dir1.dot(n1);
                long r0 = (intersection.face.origin.sub(shiftedOrigin)).dot(n0);
                long r1 = (intersection.reverse.face.origin.sub(shiftedOrigin)).dot(n1);
                Int128 det = Int128.mul(m00, m11).sub(Int128.mul(m01, m10));

                Vertex v = vertexPool.newObject();
                v.point.index = -1;
                v.copy = -1;
                v.point128.set(
                        new PointR128(
                                Int128.mul(face.dir0.x * r0, m11)
                                        .sub(Int128.mul(face.dir0.x * r1, m01))
                                        .add(Int128.mul(face.dir1.x * r1, m00))
                                        .sub(Int128.mul(face.dir1.x * r0, m10))
                                        .add(det.mul(shiftedOrigin.x)),
                                Int128.mul(face.dir0.y * r0, m11)
                                        .sub(Int128.mul(face.dir0.y * r1, m01))
                                        .add(Int128.mul(face.dir1.y * r1, m00))
                                        .sub(Int128.mul(face.dir1.y * r0, m10))
                                        .add(det.mul(shiftedOrigin.y)),
                                Int128.mul(face.dir0.z * r0, m11)
                                        .sub(Int128.mul(face.dir0.z * r1, m01))
                                        .add(Int128.mul(face.dir1.z * r1, m00))
                                        .sub(Int128.mul(face.dir1.z * r0, m10))
                                        .add(det.mul(shiftedOrigin.z)),
                                det));
                v.point.x = btScalar.cvttsd2si(v.point128.xvalue());
                v.point.y = btScalar.cvttsd2si(v.point128.yvalue());
                v.point.z = btScalar.cvttsd2si(v.point128.zvalue());
                intersection.target = v;
                v.edges = e;

                stack.push_back(v);
                stack.push_back(removed);
                stack.push_back(null);
            }

            if (cmp != 0
                    || prevCmp != 0
                    || (prevIntersection.reverse.next.target != intersection.target)) {
                faceEdge = newEdgePair(prevIntersection.target, intersection.target);
                if (prevCmp == 0) {
                    faceEdge.link(prevIntersection.reverse.next);
                }
                if ((prevCmp == 0) || prevFaceEdge != null) {
                    prevIntersection.reverse.link(faceEdge);
                }
                if (cmp == 0) {
                    intersection.reverse.prev.link(faceEdge.reverse);
                }
                faceEdge.reverse.link(intersection.reverse);
            } else {
                faceEdge = prevIntersection.reverse.next;
            }

            if (prevFaceEdge != null) {
                if (prevCmp > 0) {
                    faceEdge.link(prevFaceEdge.reverse);
                } else if (faceEdge != prevFaceEdge.reverse) {
                    stack.push_back(prevFaceEdge.target);
                    while (faceEdge.next != prevFaceEdge.reverse) {
                        Vertex removed = faceEdge.next.target;
                        removeEdgePair(faceEdge.next);
                        stack.push_back(removed);
                    }
                    stack.push_back(null);
                }
            }
            faceEdge.face = face;
            faceEdge.reverse.face = intersection.face;

            if (firstFaceEdge == null) {
                firstFaceEdge = faceEdge;
            }
        }

        if (cmp > 0) {
            firstFaceEdge.reverse.target = faceEdge.target;
            firstIntersection.reverse.link(firstFaceEdge);
            firstFaceEdge.link(faceEdge.reverse);
        } else if (firstFaceEdge != faceEdge.reverse) {
            stack.push_back(faceEdge.target);
            while (firstFaceEdge.next != faceEdge.reverse) {
                Vertex removed = firstFaceEdge.next.target;
                removeEdgePair(firstFaceEdge.next);
                stack.push_back(removed);
            }
            stack.push_back(null);
        }

        vertexList = stack.get(0);

        int pos = 0;
        while (pos < stack.size()) {
            int end = stack.size();
            while (pos < end) {
                Vertex kept = stack.get(pos++);
                boolean deeper = false;
                Vertex removed;
                while ((removed = stack.get(pos++)) != null) {
                    kept.receiveNearbyFaces(removed);
                    while (removed.edges != null) {
                        if (!deeper) {
                            deeper = true;
                            stack.push_back(kept);
                        }
                        stack.push_back(removed.edges.target);
                        removeEdgePair(removed.edges);
                    }
                }
                if (deeper) {
                    stack.push_back(null);
                }
            }
        }

        stack.resize(0);
        face.origin.set(shiftedOrigin);

        return true;
    }
}
