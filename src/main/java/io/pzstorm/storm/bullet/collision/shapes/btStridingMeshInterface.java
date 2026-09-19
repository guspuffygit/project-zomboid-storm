// Port of btStridingMeshInterface.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.nio.ByteBuffer;

/**
 * The C++ {@code unsigned char*} vertex/index bases are {@link ByteBuffer}s (little-endian, read
 * with absolute gets at byte offsets); {@code const unsigned char**} out-params are one-element
 * {@code ByteBuffer[]} holders and {@code int&}/{@code PHY_ScalarType&} out-params are one-element
 * {@code int[]} holders.
 *
 * <p>Note: no concrete subclass (btTriangleIndexVertexArray, btTriangleMesh, ...) and none of this
 * file's out-of-line functions are linked into libPZBullet — only the header-inline {@code
 * hasPremadeAabb}/{@code getPremadeAabb}. {@link #InternalProcessAllTriangles} and {@link
 * #calculateAabbBruteForce} are ported from upstream because they define the virtual contract
 * btOptimizedBvh / btTriangleMeshShape call into.
 */
public abstract class btStridingMeshInterface {
    public final btVector3 m_scaling = new btVector3(1.0, 1.0, 1.0);

    public btStridingMeshInterface() {}

    public void InternalProcessAllTriangles(
            btInternalTriangleIndexCallback callback, btVector3 aabbMin, btVector3 aabbMax) {
        int numtotalphysicsverts = 0;
        int part, graphicssubparts = getNumSubParts();
        ByteBuffer[] vertexbase = new ByteBuffer[1];
        ByteBuffer[] indexbase = new ByteBuffer[1];
        int[] indexstride = new int[1];
        int[] type = new int[1];
        int[] gfxindextype = new int[1];
        int[] stride = new int[1];
        int[] numverts = new int[1];
        int[] numtriangles = new int[1];
        int gfxindex;
        btVector3[] triangle = {new btVector3(), new btVector3(), new btVector3()};

        btVector3 meshScaling = new btVector3(getScaling());

        for (part = 0; part < graphicssubparts; part++) {
            getLockedReadOnlyVertexIndexBase(
                    vertexbase,
                    numverts,
                    type,
                    stride,
                    indexbase,
                    indexstride,
                    numtriangles,
                    gfxindextype,
                    part);
            numtotalphysicsverts += numtriangles[0] * 3; // upper bound

            ByteBuffer vb = vertexbase[0];
            ByteBuffer ib = indexbase[0];
            int st = stride[0];
            int ist = indexstride[0];
            int ntri = numtriangles[0];

            switch (type[0]) {
                case PHY_ScalarType.PHY_FLOAT:
                    {
                        switch (gfxindextype[0]) {
                            case PHY_ScalarType.PHY_INTEGER:
                            case PHY_ScalarType.PHY_SHORT:
                            case PHY_ScalarType.PHY_UCHAR:
                                for (gfxindex = 0; gfxindex < ntri; gfxindex++) {
                                    int base = gfxindex * ist;
                                    for (int k = 0; k < 3; k++) {
                                        long idx = readIndex(ib, base, k, gfxindextype[0]);
                                        int g = (int) (idx * st);
                                        triangle[k].setValue(
                                                (double) vb.getFloat(g) * meshScaling.getX(),
                                                (double) vb.getFloat(g + 4) * meshScaling.getY(),
                                                (double) vb.getFloat(g + 8) * meshScaling.getZ());
                                    }
                                    callback.internalProcessTriangleIndex(triangle, part, gfxindex);
                                }
                                break;
                            default:
                                break;
                        }
                        break;
                    }
                case PHY_ScalarType.PHY_DOUBLE:
                    {
                        switch (gfxindextype[0]) {
                            case PHY_ScalarType.PHY_INTEGER:
                            case PHY_ScalarType.PHY_SHORT:
                            case PHY_ScalarType.PHY_UCHAR:
                                for (gfxindex = 0; gfxindex < ntri; gfxindex++) {
                                    int base = gfxindex * ist;
                                    for (int k = 0; k < 3; k++) {
                                        long idx = readIndex(ib, base, k, gfxindextype[0]);
                                        int g = (int) (idx * st);
                                        triangle[k].setValue(
                                                vb.getDouble(g) * meshScaling.getX(),
                                                vb.getDouble(g + 8) * meshScaling.getY(),
                                                vb.getDouble(g + 16) * meshScaling.getZ());
                                    }
                                    callback.internalProcessTriangleIndex(triangle, part, gfxindex);
                                }
                                break;
                            default:
                                break;
                        }
                        break;
                    }
                default:
                    break;
            }

            unLockReadOnlyVertexBase(part);
        }
    }

    /** {@code tri_indices[k]} for unsigned int / unsigned short / unsigned char index arrays. */
    private static long readIndex(ByteBuffer ib, int base, int k, int indextype) {
        switch (indextype) {
            case PHY_ScalarType.PHY_INTEGER:
                return ib.getInt(base + 4 * k) & 0xFFFFFFFFL;
            case PHY_ScalarType.PHY_SHORT:
                return ib.getShort(base + 2 * k) & 0xFFFF;
            default:
                return ib.get(base + k) & 0xFF;
        }
    }

    private static final class AabbCalculationCallback extends btInternalTriangleIndexCallback {
        final btVector3 m_aabbMin = new btVector3();
        final btVector3 m_aabbMax = new btVector3();

        AabbCalculationCallback() {
            m_aabbMin.setValue(
                    btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);
            m_aabbMax.setValue(
                    -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT);
        }

        @Override
        public void internalProcessTriangleIndex(
                btVector3[] triangle, int partId, int triangleIndex) {
            m_aabbMin.setMin(triangle[0]);
            m_aabbMax.setMax(triangle[0]);
            m_aabbMin.setMin(triangle[1]);
            m_aabbMax.setMax(triangle[1]);
            m_aabbMin.setMin(triangle[2]);
            m_aabbMax.setMax(triangle[2]);
        }
    }

    public void calculateAabbBruteForce(btVector3 aabbMin, btVector3 aabbMax) {
        AabbCalculationCallback aabbCallback = new AabbCalculationCallback();
        aabbMin.setValue(
                -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT);
        aabbMax.setValue(btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);
        InternalProcessAllTriangles(aabbCallback, aabbMin, aabbMax);
        InternalProcessAllTriangles(aabbCallback, aabbMin, aabbMax);

        aabbMin.set(aabbCallback.m_aabbMin);
        aabbMax.set(aabbCallback.m_aabbMax);
    }

    public abstract void getLockedVertexIndexBase(
            ByteBuffer[] vertexbase,
            int[] numverts,
            int[] type,
            int[] stride,
            ByteBuffer[] indexbase,
            int[] indexstride,
            int[] numfaces,
            int[] indicestype,
            int subpart);

    public abstract void getLockedReadOnlyVertexIndexBase(
            ByteBuffer[] vertexbase,
            int[] numverts,
            int[] type,
            int[] stride,
            ByteBuffer[] indexbase,
            int[] indexstride,
            int[] numfaces,
            int[] indicestype,
            int subpart);

    public abstract void unLockVertexBase(int subpart);

    public abstract void unLockReadOnlyVertexBase(int subpart);

    public abstract int getNumSubParts();

    public abstract void preallocateVertices(int numverts);

    public abstract void preallocateIndices(int numindices);

    public boolean hasPremadeAabb() {
        return false;
    }

    public void setPremadeAabb(btVector3 aabbMin, btVector3 aabbMax) {}

    public void getPremadeAabb(btVector3 aabbMin, btVector3 aabbMax) {}

    public btVector3 getScaling() {
        return m_scaling;
    }

    public void setScaling(btVector3 scaling) {
        m_scaling.set(scaling);
    }
}
