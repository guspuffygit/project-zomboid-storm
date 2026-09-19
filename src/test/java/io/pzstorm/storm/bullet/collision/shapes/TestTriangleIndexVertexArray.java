package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only mirror of upstream btTriangleIndexVertexArray.cpp (Bullet 2.82), which is not linked
 * into libPZBullet and so not part of the port. Each indexed mesh is a pair of little-endian
 * ByteBuffers, laid out byte-for-byte as the C++ arrays.
 */
final class TestTriangleIndexVertexArray extends btStridingMeshInterface {
    static final class IndexedMesh {
        int m_numTriangles;
        ByteBuffer m_triangleIndexBase;
        int m_triangleIndexStride;
        int m_numVertices;
        ByteBuffer m_vertexBase;
        int m_vertexStride;
        int m_indexType = PHY_ScalarType.PHY_INTEGER;
        int m_vertexType = PHY_ScalarType.PHY_DOUBLE;
    }

    final List<IndexedMesh> m_indexedMeshes = new ArrayList<>();
    int m_hasAabb;
    final btVector3 m_aabbMin = new btVector3();
    final btVector3 m_aabbMax = new btVector3();

    void addIndexedMesh(IndexedMesh mesh, int indexType) {
        m_indexedMeshes.add(mesh);
        mesh.m_indexType = indexType;
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
        IndexedMesh mesh = m_indexedMeshes.get(subpart);
        numverts[0] = mesh.m_numVertices;
        vertexbase[0] = mesh.m_vertexBase;
        type[0] = mesh.m_vertexType;
        stride[0] = mesh.m_vertexStride;
        numfaces[0] = mesh.m_numTriangles;
        indexbase[0] = mesh.m_triangleIndexBase;
        indexstride[0] = mesh.m_triangleIndexStride;
        indicestype[0] = mesh.m_indexType;
    }

    @Override
    public void unLockVertexBase(int subpart) {}

    @Override
    public void unLockReadOnlyVertexBase(int subpart) {}

    @Override
    public int getNumSubParts() {
        return m_indexedMeshes.size();
    }

    @Override
    public void preallocateVertices(int numverts) {}

    @Override
    public void preallocateIndices(int numindices) {}

    @Override
    public boolean hasPremadeAabb() {
        return m_hasAabb == 1;
    }

    @Override
    public void setPremadeAabb(btVector3 aabbMin, btVector3 aabbMax) {
        m_aabbMin.set(aabbMin);
        m_aabbMax.set(aabbMax);
        m_hasAabb = 1;
    }

    @Override
    public void getPremadeAabb(btVector3 aabbMin, btVector3 aabbMax) {
        aabbMin.set(m_aabbMin);
        aabbMax.set(m_aabbMax);
    }
}
