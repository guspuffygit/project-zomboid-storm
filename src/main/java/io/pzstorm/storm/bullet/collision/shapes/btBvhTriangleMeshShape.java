// Port of btBvhTriangleMeshShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.collision.broadphase.btNodeOverlapCallback;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.nio.ByteBuffer;

/**
 * Static concave triangle mesh with a BVH. serialize / serializeSingleBvh /
 * serializeSingleTriangleInfoMap / calculateSerializeBufferSize are linked but never called and are
 * not ported (docs/re-bullet/shapes.md). The destructor (frees an owned bvh) has no Java
 * equivalent.
 */
public class btBvhTriangleMeshShape extends btTriangleMeshShape {
    public btOptimizedBvh m_bvh;
    public btTriangleInfoMap m_triangleInfoMap;

    public boolean m_useQuantizedAabbCompression;
    public boolean m_ownsBvh;

    public btBvhTriangleMeshShape(
            btStridingMeshInterface meshInterface, boolean useQuantizedAabbCompression) {
        this(meshInterface, useQuantizedAabbCompression, true);
    }

    public btBvhTriangleMeshShape(
            btStridingMeshInterface meshInterface,
            boolean useQuantizedAabbCompression,
            boolean buildBvh) {
        super(meshInterface);
        m_bvh = null;
        m_triangleInfoMap = null;
        m_useQuantizedAabbCompression = useQuantizedAabbCompression;
        m_ownsBvh = false;
        m_shapeType = BroadphaseNativeTypes.TRIANGLE_MESH_SHAPE_PROXYTYPE;
        // construct bvh from meshInterface
        if (buildBvh) {
            buildOptimizedBvh();
        }
    }

    public btBvhTriangleMeshShape(
            btStridingMeshInterface meshInterface,
            boolean useQuantizedAabbCompression,
            btVector3 bvhAabbMin,
            btVector3 bvhAabbMax) {
        this(meshInterface, useQuantizedAabbCompression, bvhAabbMin, bvhAabbMax, true);
    }

    public btBvhTriangleMeshShape(
            btStridingMeshInterface meshInterface,
            boolean useQuantizedAabbCompression,
            btVector3 bvhAabbMin,
            btVector3 bvhAabbMax,
            boolean buildBvh) {
        super(meshInterface);
        m_bvh = null;
        m_triangleInfoMap = null;
        m_useQuantizedAabbCompression = useQuantizedAabbCompression;
        m_ownsBvh = false;
        m_shapeType = BroadphaseNativeTypes.TRIANGLE_MESH_SHAPE_PROXYTYPE;
        // construct bvh from meshInterface
        if (buildBvh) {
            m_bvh = new btOptimizedBvh();

            m_bvh.build(meshInterface, m_useQuantizedAabbCompression, bvhAabbMin, bvhAabbMax);
            m_ownsBvh = true;
        }
    }

    public boolean getOwnsBvh() {
        return m_ownsBvh;
    }

    public void partialRefitTree(btVector3 aabbMin, btVector3 aabbMax) {
        m_bvh.refitPartial(m_meshInterface, aabbMin, aabbMax);

        m_localAabbMin.setMin(aabbMin);
        m_localAabbMax.setMax(aabbMax);
    }

    public void refitTree(btVector3 aabbMin, btVector3 aabbMax) {
        m_bvh.refit(m_meshInterface, aabbMin, aabbMax);

        recalcLocalAabb();
    }

    /**
     * Reads the three vertices of triangle {@code nodeTriangleIndex} of the locked sub part into
     * {@code triangle}, j = 2..0, as the MyNodeOverlapCallback::processNode bodies do. {@code
     * handleUchar}: processAllTriangles' variant reads PHY_UCHAR indices as bytes; the raycast and
     * convexcast variants read every non-SHORT index type as unsigned int.
     */
    static void readTriangle(
            btVector3[] triangle,
            ByteBuffer vertexbase,
            int type,
            int stride,
            ByteBuffer indexbase,
            int indexstride,
            int indicestype,
            int nodeTriangleIndex,
            btVector3 meshScaling,
            boolean handleUchar) {
        int gfxbase = nodeTriangleIndex * indexstride;
        for (int j = 2; j >= 0; j--) {
            int graphicsindex;
            if (indicestype == PHY_ScalarType.PHY_SHORT) {
                graphicsindex = indexbase.getShort(gfxbase + 2 * j) & 0xFFFF;
            } else if (!handleUchar || indicestype == PHY_ScalarType.PHY_INTEGER) {
                graphicsindex = indexbase.getInt(gfxbase + 4 * j);
            } else {
                graphicsindex = indexbase.get(gfxbase + j) & 0xFF;
            }

            int graphicsbase = graphicsindex * stride;
            if (type == PHY_ScalarType.PHY_FLOAT) {
                triangle[j].set(
                        new btVector3(
                                (double) vertexbase.getFloat(graphicsbase) * meshScaling.getX(),
                                (double) vertexbase.getFloat(graphicsbase + 4) * meshScaling.getY(),
                                (double) vertexbase.getFloat(graphicsbase + 8)
                                        * meshScaling.getZ()));
            } else {
                triangle[j].set(
                        new btVector3(
                                vertexbase.getDouble(graphicsbase) * meshScaling.getX(),
                                vertexbase.getDouble(graphicsbase + 8) * meshScaling.getY(),
                                vertexbase.getDouble(graphicsbase + 16) * meshScaling.getZ()));
            }
        }
    }

    /**
     * Local struct MyNodeOverlapCallback of performRaycast / performConvexcast (identical bodies;
     * {@code btVector3 m_triangle[3]} is a local of processNode there).
     */
    static final class CastNodeOverlapCallback extends btNodeOverlapCallback {
        final btStridingMeshInterface m_meshInterface;
        final btTriangleCallback m_callback;

        CastNodeOverlapCallback(
                btTriangleCallback callback, btStridingMeshInterface meshInterface) {
            m_meshInterface = meshInterface;
            m_callback = callback;
        }

        @Override
        public void processNode(int nodeSubPart, int nodeTriangleIndex) {
            btVector3[] m_triangle = {new btVector3(), new btVector3(), new btVector3()};
            ByteBuffer[] vertexbase = new ByteBuffer[1];
            int[] numverts = new int[1];
            int[] type = new int[1];
            int[] stride = new int[1];
            ByteBuffer[] indexbase = new ByteBuffer[1];
            int[] indexstride = new int[1];
            int[] numfaces = new int[1];
            int[] indicestype = new int[1];

            m_meshInterface.getLockedReadOnlyVertexIndexBase(
                    vertexbase,
                    numverts,
                    type,
                    stride,
                    indexbase,
                    indexstride,
                    numfaces,
                    indicestype,
                    nodeSubPart);

            btVector3 meshScaling = m_meshInterface.getScaling();
            readTriangle(
                    m_triangle,
                    vertexbase[0],
                    type[0],
                    stride[0],
                    indexbase[0],
                    indexstride[0],
                    indicestype[0],
                    nodeTriangleIndex,
                    meshScaling,
                    false);

            /* Perform ray vs. triangle collision here */
            m_callback.processTriangle(m_triangle, nodeSubPart, nodeTriangleIndex);
            m_meshInterface.unLockReadOnlyVertexBase(nodeSubPart);
        }
    }

    public void performRaycast(
            btTriangleCallback callback, btVector3 raySource, btVector3 rayTarget) {
        CastNodeOverlapCallback myNodeCallback =
                new CastNodeOverlapCallback(callback, m_meshInterface);

        m_bvh.reportRayOverlappingNodex(myNodeCallback, raySource, rayTarget);
    }

    public void performConvexcast(
            btTriangleCallback callback,
            btVector3 raySource,
            btVector3 rayTarget,
            btVector3 aabbMin,
            btVector3 aabbMax) {
        CastNodeOverlapCallback myNodeCallback =
                new CastNodeOverlapCallback(callback, m_meshInterface);

        m_bvh.reportBoxCastOverlappingNodex(myNodeCallback, raySource, rayTarget, aabbMin, aabbMax);
    }

    /**
     * Local struct MyNodeOverlapCallback of processAllTriangles ({@code m_triangle[3]} is a member,
     * reused across processNode calls).
     */
    static final class AllTrianglesNodeOverlapCallback extends btNodeOverlapCallback {
        final btStridingMeshInterface m_meshInterface;
        final btTriangleCallback m_callback;
        final btVector3[] m_triangle = {new btVector3(), new btVector3(), new btVector3()};

        AllTrianglesNodeOverlapCallback(
                btTriangleCallback callback, btStridingMeshInterface meshInterface) {
            m_meshInterface = meshInterface;
            m_callback = callback;
        }

        @Override
        public void processNode(int nodeSubPart, int nodeTriangleIndex) {
            ByteBuffer[] vertexbase = new ByteBuffer[1];
            int[] numverts = new int[1];
            int[] type = new int[1];
            int[] stride = new int[1];
            ByteBuffer[] indexbase = new ByteBuffer[1];
            int[] indexstride = new int[1];
            int[] numfaces = new int[1];
            int[] indicestype = new int[1];

            m_meshInterface.getLockedReadOnlyVertexIndexBase(
                    vertexbase,
                    numverts,
                    type,
                    stride,
                    indexbase,
                    indexstride,
                    numfaces,
                    indicestype,
                    nodeSubPart);

            btVector3 meshScaling = m_meshInterface.getScaling();
            readTriangle(
                    m_triangle,
                    vertexbase[0],
                    type[0],
                    stride[0],
                    indexbase[0],
                    indexstride[0],
                    indicestype[0],
                    nodeTriangleIndex,
                    meshScaling,
                    true);

            m_callback.processTriangle(m_triangle, nodeSubPart, nodeTriangleIndex);
            m_meshInterface.unLockReadOnlyVertexBase(nodeSubPart);
        }
    }

    /** perform bvh tree traversal and report overlapping triangles to 'callback' */
    @Override
    public void processAllTriangles(
            btTriangleCallback callback, btVector3 aabbMin, btVector3 aabbMax) {
        AllTrianglesNodeOverlapCallback myNodeCallback =
                new AllTrianglesNodeOverlapCallback(callback, m_meshInterface);

        m_bvh.reportAabbOverlappingNodex(myNodeCallback, aabbMin, aabbMax);
    }

    @Override
    public String getName() {
        return "BVHTRIANGLEMESH";
    }

    @Override
    public void setLocalScaling(btVector3 scaling) {
        if (getLocalScaling().sub(scaling).length2() > btScalar.SIMD_EPSILON) {
            super.setLocalScaling(scaling);
            buildOptimizedBvh();
        }
    }

    public btOptimizedBvh getOptimizedBvh() {
        return m_bvh;
    }

    public void setOptimizedBvh(btOptimizedBvh bvh) {
        setOptimizedBvh(bvh, new btVector3(1, 1, 1));
    }

    public void setOptimizedBvh(btOptimizedBvh bvh, btVector3 scaling) {
        m_bvh = bvh;
        m_ownsBvh = false;
        // update the scaling without rebuilding the bvh
        if (getLocalScaling().sub(scaling).length2() > btScalar.SIMD_EPSILON) {
            super.setLocalScaling(scaling);
        }
    }

    public void buildOptimizedBvh() {
        /// m_localAabbMin/m_localAabbMax is already re-calculated in btTriangleMeshShape. We could
        // just scale aabb, but this needs some more work
        m_bvh = new btOptimizedBvh();
        // rebuild the bvh...
        m_bvh.build(m_meshInterface, m_useQuantizedAabbCompression, m_localAabbMin, m_localAabbMax);
        m_ownsBvh = true;
    }

    public boolean usesQuantizedAabbCompression() {
        return m_useQuantizedAabbCompression;
    }

    public void setTriangleInfoMap(btTriangleInfoMap triangleInfoMap) {
        m_triangleInfoMap = triangleInfoMap;
    }

    public btTriangleInfoMap getTriangleInfoMap() {
        return m_triangleInfoMap;
    }
}
