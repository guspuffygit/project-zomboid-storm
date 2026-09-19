package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bit-exact differential test of btStridingMeshInterface / btBvhTriangleMeshShape / btOptimizedBvh
 * (quantized and not) / btStaticPlaneShape against upstream Bullet 2.82 (g++ 10.5 -O3
 * -DBT_USE_DOUBLE_PRECISION, x86-64). mesh-golden.txt is the C++ driver's output for exactly the
 * calls below, including the order in which the BVH traversal reports triangles.
 */
class MeshGoldenTest implements UnitTest {

    /**
     * The C++ goldens ran in a fresh process. Other test classes in this JVM (the PZ world sets
     * gDeactivationTime and gContactAddedCallback) leave Bullet globals changed, so restore them.
     */
    @BeforeEach
    void freshLibrary() {
        btGlobals.resetAll();
        GlibcRand.srand(1);
    }

    private final List<String> out = new ArrayList<>();

    private static String h(double d) {
        return Double.isNaN(d) ? " nan" : String.format(" %016x", Double.doubleToRawLongBits(d));
    }

    private void PV(String t, btVector3 v) {
        out.add(t + h(v.x()) + h(v.y()) + h(v.z()));
    }

    private final class Printer extends btTriangleCallback {
        final String tag;

        Printer(String tag) {
            this.tag = tag;
        }

        @Override
        public void processTriangle(btVector3[] tr, int partId, int triIndex) {
            out.add(tag + " " + partId + " " + triIndex);
            PV(" a", tr[0]);
            PV(" b", tr[1]);
            PV(" c", tr[2]);
        }
    }

    private final class IPrinter extends btInternalTriangleIndexCallback {
        @Override
        public void internalProcessTriangleIndex(btVector3[] tr, int partId, int triIndex) {
            out.add("itri " + partId + " " + triIndex);
            PV(" a", tr[0]);
            PV(" b", tr[1]);
            PV(" c", tr[2]);
        }
    }

    private static btTransform T1() {
        btQuaternion q = new btQuaternion(0.1, 0.3, -0.2, 0.9);
        q.normalize();
        return new btTransform(q, new btVector3(1.5, -2, 0.25));
    }

    private static ByteBuffer le(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    // grid: part0 6x5 double verts (stride 24), int indices; part1 4x4 float verts (stride 12),
    // short indices
    private final ByteBuffer v0 = le(6 * 5 * 3 * 8);
    private final ByteBuffer i0 = le(5 * 4 * 2 * 3 * 4);
    private final ByteBuffer v1 = le(4 * 4 * 3 * 4);
    private final ByteBuffer i1 = le(3 * 3 * 2 * 3 * 2);

    private void build() {
        int n = 0;
        for (int j = 0; j < 5; j++) {
            for (int i = 0; i < 6; i++) {
                v0.putDouble(8 * n++, i * 0.7 - 1.3);
                v0.putDouble(8 * n++, 0.1 * ((i * 7 + j * 3) % 5) - 0.2);
                v0.putDouble(8 * n++, j * 0.9 - 2.1);
            }
        }
        n = 0;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 5; i++) {
                int a = j * 6 + i, b = a + 1, c = a + 6, d = c + 1;
                for (int x : new int[] {a, c, b, b, c, d}) {
                    i0.putInt(4 * n++, x);
                }
            }
        }
        n = 0;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                v1.putFloat(4 * n++, (float) (i * 0.55 + 2.0));
                v1.putFloat(4 * n++, (float) (0.3 + 0.07 * ((i + 2 * j) % 3)));
                v1.putFloat(4 * n++, (float) (j * 0.6 - 1.0));
            }
        }
        n = 0;
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                int a = j * 4 + i, b = a + 1, c = a + 4, d = c + 1;
                for (int x : new int[] {a, c, b, b, c, d}) {
                    i1.putShort(2 * n++, (short) x);
                }
            }
        }
    }

    private TestTriangleIndexVertexArray mkmesh() {
        TestTriangleIndexVertexArray m = new TestTriangleIndexVertexArray();
        TestTriangleIndexVertexArray.IndexedMesh a = new TestTriangleIndexVertexArray.IndexedMesh();
        a.m_numTriangles = 40;
        a.m_triangleIndexBase = i0;
        a.m_triangleIndexStride = 12;
        a.m_numVertices = 30;
        a.m_vertexBase = v0;
        a.m_vertexStride = 24;
        a.m_vertexType = PHY_ScalarType.PHY_DOUBLE;
        m.addIndexedMesh(a, PHY_ScalarType.PHY_INTEGER);
        TestTriangleIndexVertexArray.IndexedMesh b = new TestTriangleIndexVertexArray.IndexedMesh();
        b.m_numTriangles = 18;
        b.m_triangleIndexBase = i1;
        b.m_triangleIndexStride = 6;
        b.m_numVertices = 16;
        b.m_vertexBase = v1;
        b.m_vertexStride = 12;
        b.m_vertexType = PHY_ScalarType.PHY_FLOAT;
        m.addIndexedMesh(b, PHY_ScalarType.PHY_SHORT);
        return m;
    }

    private void queries(btBvhTriangleMeshShape s) {
        btVector3 mn = new btVector3(), mx = new btVector3();
        s.getAabb(T1(), mn, mx);
        PV("aabb.min", mn);
        PV("aabb.max", mx);
        PV("lmin", s.getLocalAabbMin());
        PV("lmax", s.getLocalAabbMax());
        PV("sup", s.localGetSupportingVertex(new btVector3(0.2, 1, -0.3)));
        btVector3 in = new btVector3();
        s.calculateLocalInertia(1, in);
        PV("in", in);
        s.processAllTriangles(
                new Printer("all"), new btVector3(-0.5, -1, -1.2), new btVector3(0.9, 1, 0.3));
        s.processAllTriangles(
                new Printer("all2"), new btVector3(1.8, 0, -0.5), new btVector3(2.9, 1, 0.4));
        s.performRaycast(new Printer("ray"), new btVector3(-2, 2, -2.5), new btVector3(3, -1, 1.5));
        s.performRaycast(
                new Printer("ray2"), new btVector3(2.3, 5, -0.7), new btVector3(2.4, -5, -0.6));
        s.performConvexcast(
                new Printer("cast"),
                new btVector3(-1, 1, -1),
                new btVector3(2.5, 0.2, 0.5),
                new btVector3(-0.2, -0.2, -0.2),
                new btVector3(0.2, 0.2, 0.2));
    }

    private void run() {
        build();
        out.add("#iface");
        {
            TestTriangleIndexVertexArray m = mkmesh();
            IPrinter ip = new IPrinter();
            m.InternalProcessAllTriangles(
                    ip, new btVector3(-1e30, -1e30, -1e30), new btVector3(1e30, 1e30, 1e30));
            btVector3 mn = new btVector3(), mx = new btVector3();
            m.calculateAabbBruteForce(mn, mx);
            PV("bf.min", mn);
            PV("bf.max", mx);
            m.setScaling(new btVector3(2, 1, 0.5));
            m.InternalProcessAllTriangles(ip, new btVector3(0, 0, 0), new btVector3(0, 0, 0));
        }
        for (int q = 0; q < 2; q++) {
            out.add("#bvh " + q);
            TestTriangleIndexVertexArray m = mkmesh();
            btBvhTriangleMeshShape s = new btBvhTriangleMeshShape(m, q != 0);
            queries(s);
            s.setLocalScaling(new btVector3(1, 2, 0.5));
            PV("scale", s.getLocalScaling());
            queries(s);
            if (q != 0) {
                v0.putDouble(8 * (3 * 7 + 1), v0.getDouble(8 * (3 * 7 + 1)) + 0.8);
                s.partialRefitTree(new btVector3(-1, -1, -2), new btVector3(0, 2, -1));
                queries(s);
                float f = v1.getFloat(4 * (3 * 5 + 1));
                f -= 0.5;
                v1.putFloat(4 * (3 * 5 + 1), f);
                s.refitTree(new btVector3(-5, -5, -5), new btVector3(5, 5, 5));
                queries(s);
            }
            build();
        }
        out.add("#bvhaabb");
        {
            TestTriangleIndexVertexArray m = mkmesh();
            btBvhTriangleMeshShape s =
                    new btBvhTriangleMeshShape(
                            m, true, new btVector3(-3, -3, -3), new btVector3(4, 3, 3));
            queries(s);
        }
        out.add("#plane");
        {
            btStaticPlaneShape pl = new btStaticPlaneShape(new btVector3(0.1, 1, 0.2), 0.5);
            PV("n", pl.getPlaneNormal());
            pl.processAllTriangles(
                    new Printer("pl"), new btVector3(-1, -2, -0.5), new btVector3(2, 1, 3));
            btVector3 mn = new btVector3(), mx = new btVector3();
            pl.getAabb(T1(), mn, mx);
            PV("aabb.min", mn);
            PV("aabb.max", mx);
        }
    }

    @Test
    void matchesUpstreamBitExact() throws IOException {
        run();
        ShapesGoldenTest.compare(ShapesGoldenTest.golden("mesh-golden.txt"), out);
    }
}
