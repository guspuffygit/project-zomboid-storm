package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.shapes.PHY_ScalarType;
import io.pzstorm.storm.bullet.collision.shapes.btStridingMeshInterface;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only mirror of upstream btTriangleMesh (Bullet 2.82) with its defaults: 32-bit indices,
 * 4-component double vertices (stride sizeof(btVector3) = 32), one indexed mesh, and addTriangle
 * without duplicate-vertex removal. btTriangleMesh/btTriangleIndexVertexArray are not linked into
 * libPZBullet, so they are not part of the port; this exists only to feed btBvhTriangleMeshShape
 * the same data the C++ differential driver uses.
 */
final class TestTriangleMesh extends btStridingMeshInterface {
    private final List<btVector3> m_4componentVertices = new ArrayList<>();
    private final List<Integer> m_32bitIndices = new ArrayList<>();
    private int m_numTriangles;

    void addTriangle(btVector3 v0, btVector3 v1, btVector3 v2) {
        m_numTriangles++;
        addIndex(findOrAddVertex(v0));
        addIndex(findOrAddVertex(v1));
        addIndex(findOrAddVertex(v2));
    }

    private int findOrAddVertex(btVector3 v) {
        m_4componentVertices.add(new btVector3(v));
        return m_4componentVertices.size() - 1;
    }

    private void addIndex(int index) {
        m_32bitIndices.add(index);
    }

    @Override
    public void getLockedVertexIndexBase(
            ByteBuffer[] vertexbase,
            int[] numverts,
            int[] type,
            int[] stride,
            ByteBuffer[] indexbase,
            int[] indexstride,
            int[] numfaces,
            int[] indicestype,
            int subpart) {
        getLockedReadOnlyVertexIndexBase(
                vertexbase,
                numverts,
                type,
                stride,
                indexbase,
                indexstride,
                numfaces,
                indicestype,
                subpart);
    }

    @Override
    public void getLockedReadOnlyVertexIndexBase(
            ByteBuffer[] vertexbase,
            int[] numverts,
            int[] type,
            int[] stride,
            ByteBuffer[] indexbase,
            int[] indexstride,
            int[] numfaces,
            int[] indicestype,
            int subpart) {
        ByteBuffer vb =
                ByteBuffer.allocate(32 * m_4componentVertices.size())
                        .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < m_4componentVertices.size(); i++) {
            btVector3 v = m_4componentVertices.get(i);
            vb.putDouble(32 * i, v.x());
            vb.putDouble(32 * i + 8, v.y());
            vb.putDouble(32 * i + 16, v.z());
            vb.putDouble(32 * i + 24, v.w());
        }
        ByteBuffer ib =
                ByteBuffer.allocate(4 * m_32bitIndices.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < m_32bitIndices.size(); i++) ib.putInt(4 * i, m_32bitIndices.get(i));
        numverts[0] = m_4componentVertices.size();
        vertexbase[0] = vb;
        type[0] = PHY_ScalarType.PHY_DOUBLE;
        stride[0] = 32;
        numfaces[0] = m_numTriangles;
        indexbase[0] = ib;
        indexstride[0] = 12;
        indicestype[0] = PHY_ScalarType.PHY_INTEGER;
    }

    @Override
    public void unLockVertexBase(int subpart) {}

    @Override
    public void unLockReadOnlyVertexBase(int subpart) {}

    @Override
    public int getNumSubParts() {
        return 1;
    }

    @Override
    public void preallocateVertices(int numverts) {}

    @Override
    public void preallocateIndices(int numindices) {}
}
