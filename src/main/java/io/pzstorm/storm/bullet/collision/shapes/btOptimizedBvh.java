// Port of btOptimizedBvh.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.btBvhSubtreeInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btOptimizedBvhNode;
import io.pzstorm.storm.bullet.collision.broadphase.btQuantizedBvh;
import io.pzstorm.storm.bullet.collision.broadphase.btQuantizedBvhNode;
import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.nio.ByteBuffer;

/**
 * The OptimizedBvh extends the btQuantizedBvh to create AABB tree for triangle meshes, through the
 * btStridingMeshInterface. serializeInPlace/deSerializeInPlace are linked but never called (see
 * docs/re-bullet/shapes.md) and are not ported.
 */
public class btOptimizedBvh extends btQuantizedBvh {

    public btOptimizedBvh() {
        super();
    }

    /** Local struct NodeTriangleCallback of build(). */
    static final class NodeTriangleCallback extends btInternalTriangleIndexCallback {
        final btAlignedObjectArray<btOptimizedBvhNode> m_triangleNodes;

        NodeTriangleCallback(btAlignedObjectArray<btOptimizedBvhNode> triangleNodes) {
            m_triangleNodes = triangleNodes;
        }

        @Override
        public void internalProcessTriangleIndex(
                btVector3[] triangle, int partId, int triangleIndex) {
            btOptimizedBvhNode node = new btOptimizedBvhNode();
            btVector3 aabbMin = new btVector3(), aabbMax = new btVector3();
            aabbMin.setValue(
                    btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);
            aabbMax.setValue(
                    -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT);
            aabbMin.setMin(triangle[0]);
            aabbMax.setMax(triangle[0]);
            aabbMin.setMin(triangle[1]);
            aabbMax.setMax(triangle[1]);
            aabbMin.setMin(triangle[2]);
            aabbMax.setMax(triangle[2]);

            // with quantization?
            node.m_aabbMinOrg.set(aabbMin);
            node.m_aabbMaxOrg.set(aabbMax);

            node.m_escapeIndex = -1;

            // for child nodes
            node.m_subPart = partId;
            node.m_triangleIndex = triangleIndex;
            m_triangleNodes.push_back(node);
        }
    }

    /** Local struct QuantizedNodeTriangleCallback of build(). */
    static final class QuantizedNodeTriangleCallback extends btInternalTriangleIndexCallback {
        final btAlignedObjectArray<btQuantizedBvhNode> m_triangleNodes;
        final btQuantizedBvh m_optimizedTree; // for quantization

        QuantizedNodeTriangleCallback(
                btAlignedObjectArray<btQuantizedBvhNode> triangleNodes, btQuantizedBvh tree) {
            m_triangleNodes = triangleNodes;
            m_optimizedTree = tree;
        }

        @Override
        public void internalProcessTriangleIndex(
                btVector3[] triangle, int partId, int triangleIndex) {
            btQuantizedBvhNode node = new btQuantizedBvhNode();
            btVector3 aabbMin = new btVector3(), aabbMax = new btVector3();
            aabbMin.setValue(
                    btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);
            aabbMax.setValue(
                    -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT, -btScalar.BT_LARGE_FLOAT);
            aabbMin.setMin(triangle[0]);
            aabbMax.setMax(triangle[0]);
            aabbMin.setMin(triangle[1]);
            aabbMax.setMax(triangle[1]);
            aabbMin.setMin(triangle[2]);
            aabbMax.setMax(triangle[2]);

            // PCK: add these checks for zero dimensions of aabb
            final double MIN_AABB_DIMENSION = 0.002;
            final double MIN_AABB_HALF_DIMENSION = 0.001;
            if (aabbMax.x() - aabbMin.x() < MIN_AABB_DIMENSION) {
                aabbMax.setX(aabbMax.x() + MIN_AABB_HALF_DIMENSION);
                aabbMin.setX(aabbMin.x() - MIN_AABB_HALF_DIMENSION);
            }
            if (aabbMax.y() - aabbMin.y() < MIN_AABB_DIMENSION) {
                aabbMax.setY(aabbMax.y() + MIN_AABB_HALF_DIMENSION);
                aabbMin.setY(aabbMin.y() - MIN_AABB_HALF_DIMENSION);
            }
            if (aabbMax.z() - aabbMin.z() < MIN_AABB_DIMENSION) {
                aabbMax.setZ(aabbMax.z() + MIN_AABB_HALF_DIMENSION);
                aabbMin.setZ(aabbMin.z() - MIN_AABB_HALF_DIMENSION);
            }

            m_optimizedTree.quantize(node.m_quantizedAabbMin, aabbMin, 0);
            m_optimizedTree.quantize(node.m_quantizedAabbMax, aabbMax, 1);

            node.m_escapeIndexOrTriangleIndex =
                    (partId << (31 - btQuantizedBvh.MAX_NUM_PARTS_IN_BITS)) | triangleIndex;

            m_triangleNodes.push_back(node);
        }
    }

    public void build(
            btStridingMeshInterface triangles,
            boolean useQuantizedAabbCompression,
            btVector3 bvhAabbMin,
            btVector3 bvhAabbMax) {
        m_useQuantization = useQuantizedAabbCompression;

        int numLeafNodes = 0;

        if (m_useQuantization) {
            // initialize quantization values
            setQuantizationValues(bvhAabbMin, bvhAabbMax);

            QuantizedNodeTriangleCallback callback =
                    new QuantizedNodeTriangleCallback(m_quantizedLeafNodes, this);

            triangles.InternalProcessAllTriangles(callback, m_bvhAabbMin, m_bvhAabbMax);

            // now we have an array of leafnodes in m_leafNodes
            numLeafNodes = m_quantizedLeafNodes.size();

            m_quantizedContiguousNodes.resize(2 * numLeafNodes);
        } else {
            NodeTriangleCallback callback = new NodeTriangleCallback(m_leafNodes);

            btVector3 aabbMin =
                    new btVector3(
                            -btScalar.BT_LARGE_FLOAT,
                            -btScalar.BT_LARGE_FLOAT,
                            -btScalar.BT_LARGE_FLOAT);
            btVector3 aabbMax =
                    new btVector3(
                            btScalar.BT_LARGE_FLOAT,
                            btScalar.BT_LARGE_FLOAT,
                            btScalar.BT_LARGE_FLOAT);

            triangles.InternalProcessAllTriangles(callback, aabbMin, aabbMax);

            // now we have an array of leafnodes in m_leafNodes
            numLeafNodes = m_leafNodes.size();

            m_contiguousNodes.resize(2 * numLeafNodes);
        }

        m_curNodeIndex = 0;

        buildTree(0, numLeafNodes);

        /// if the entire tree is small then subtree size, we need to create a header info for the
        // tree
        if (m_useQuantization && m_SubtreeHeaders.size() == 0) {
            btBvhSubtreeInfo subtree = m_SubtreeHeaders.expand();
            subtree.setAabbFromQuantizeNode(m_quantizedContiguousNodes.get(0));
            subtree.m_rootNodeIndex = 0;
            subtree.m_subtreeSize =
                    m_quantizedContiguousNodes.get(0).isLeafNode()
                            ? 1
                            : m_quantizedContiguousNodes.get(0).getEscapeIndex();
        }

        // PCK: update the copy of the size
        m_subtreeHeaderCount = m_SubtreeHeaders.size();

        // PCK: clear m_quantizedLeafNodes and m_leafNodes, they are temporary
        m_quantizedLeafNodes.clear();
        m_leafNodes.clear();
    }

    public void refit(btStridingMeshInterface meshInterface, btVector3 aabbMin, btVector3 aabbMax) {
        if (m_useQuantization) {
            setQuantizationValues(aabbMin, aabbMax);

            updateBvhNodes(meshInterface, 0, m_curNodeIndex, 0);

            /// now update all subtree headers

            int i;
            for (i = 0; i < m_SubtreeHeaders.size(); i++) {
                btBvhSubtreeInfo subtree = m_SubtreeHeaders.get(i);
                subtree.setAabbFromQuantizeNode(
                        m_quantizedContiguousNodes.get(subtree.m_rootNodeIndex));
            }
        }
    }

    public void refitPartial(
            btStridingMeshInterface meshInterface, btVector3 aabbMin, btVector3 aabbMax) {
        // incrementally initialize quantization values

        int[] quantizedQueryAabbMin = new int[3];
        int[] quantizedQueryAabbMax = new int[3];

        quantize(quantizedQueryAabbMin, aabbMin, 0);
        quantize(quantizedQueryAabbMax, aabbMax, 1);

        int i;
        for (i = 0; i < this.m_SubtreeHeaders.size(); i++) {
            btBvhSubtreeInfo subtree = m_SubtreeHeaders.get(i);

            // PCK: unsigned instead of bool
            int overlap =
                    btAabbUtil2.testQuantizedAabbAgainstQuantizedAabb(
                            quantizedQueryAabbMin,
                            quantizedQueryAabbMax,
                            subtree.m_quantizedAabbMin,
                            subtree.m_quantizedAabbMax);
            if (overlap != 0) {
                updateBvhNodes(
                        meshInterface,
                        subtree.m_rootNodeIndex,
                        subtree.m_rootNodeIndex + subtree.m_subtreeSize,
                        i);

                subtree.setAabbFromQuantizeNode(
                        m_quantizedContiguousNodes.get(subtree.m_rootNodeIndex));
            }
        }
    }

    public void updateBvhNodes(
            btStridingMeshInterface meshInterface, int firstNode, int endNode, int index) {
        int curNodeSubPart = -1;

        // get access info to trianglemesh data
        ByteBuffer[] vertexbase = new ByteBuffer[1];
        int[] numverts = new int[1];
        int[] type = {PHY_ScalarType.PHY_INTEGER};
        int[] stride = new int[1];
        ByteBuffer[] indexbase = new ByteBuffer[1];
        int[] indexstride = new int[1];
        int[] numfaces = new int[1];
        int[] indicestype = {PHY_ScalarType.PHY_INTEGER};

        btVector3[] triangleVerts = {new btVector3(), new btVector3(), new btVector3()};
        btVector3 aabbMin = new btVector3(), aabbMax = new btVector3();
        btVector3 meshScaling = meshInterface.getScaling();

        int i;
        for (i = endNode - 1; i >= firstNode; i--) {
            btQuantizedBvhNode curNode = m_quantizedContiguousNodes.get(i);
            if (curNode.isLeafNode()) {
                // recalc aabb from triangle data
                int nodeSubPart = curNode.getPartId();
                int nodeTriangleIndex = curNode.getTriangleIndex();
                if (nodeSubPart != curNodeSubPart) {
                    if (curNodeSubPart >= 0) meshInterface.unLockReadOnlyVertexBase(curNodeSubPart);
                    meshInterface.getLockedReadOnlyVertexIndexBase(
                            vertexbase,
                            numverts,
                            type,
                            stride,
                            indexbase,
                            indexstride,
                            numfaces,
                            indicestype,
                            nodeSubPart);

                    curNodeSubPart = nodeSubPart;
                }

                int gfxbase = nodeTriangleIndex * indexstride[0];

                for (int j = 2; j >= 0; j--) {
                    // upstream reads unsigned int for every non-SHORT index type (PHY_UCHAR
                    // included)
                    int graphicsindex =
                            indicestype[0] == PHY_ScalarType.PHY_SHORT
                                    ? (indexbase[0].getShort(gfxbase + 2 * j) & 0xFFFF)
                                    : indexbase[0].getInt(gfxbase + 4 * j);
                    if (type[0] == PHY_ScalarType.PHY_FLOAT) {
                        int graphicsbase = graphicsindex * stride[0];
                        ByteBuffer vb = vertexbase[0];
                        triangleVerts[j].set(
                                new btVector3(
                                        (double) vb.getFloat(graphicsbase) * meshScaling.getX(),
                                        (double) vb.getFloat(graphicsbase + 4) * meshScaling.getY(),
                                        (double) vb.getFloat(graphicsbase + 8)
                                                * meshScaling.getZ()));
                    } else {
                        int graphicsbase = graphicsindex * stride[0];
                        ByteBuffer vb = vertexbase[0];
                        triangleVerts[j].set(
                                new btVector3(
                                        vb.getDouble(graphicsbase) * meshScaling.getX(),
                                        vb.getDouble(graphicsbase + 8) * meshScaling.getY(),
                                        vb.getDouble(graphicsbase + 16) * meshScaling.getZ()));
                    }
                }

                aabbMin.setValue(
                        btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);
                aabbMax.setValue(
                        -btScalar.BT_LARGE_FLOAT,
                        -btScalar.BT_LARGE_FLOAT,
                        -btScalar.BT_LARGE_FLOAT);
                aabbMin.setMin(triangleVerts[0]);
                aabbMax.setMax(triangleVerts[0]);
                aabbMin.setMin(triangleVerts[1]);
                aabbMax.setMax(triangleVerts[1]);
                aabbMin.setMin(triangleVerts[2]);
                aabbMax.setMax(triangleVerts[2]);

                quantize(curNode.m_quantizedAabbMin, aabbMin, 0);
                quantize(curNode.m_quantizedAabbMax, aabbMax, 1);
            } else {
                // combine aabb from both children

                btQuantizedBvhNode leftChildNode = m_quantizedContiguousNodes.get(i + 1);

                btQuantizedBvhNode rightChildNode =
                        leftChildNode.isLeafNode()
                                ? m_quantizedContiguousNodes.get(i + 2)
                                : m_quantizedContiguousNodes.get(
                                        i + 1 + leftChildNode.getEscapeIndex());

                for (int k = 0; k < 3; k++) {
                    curNode.m_quantizedAabbMin[k] = leftChildNode.m_quantizedAabbMin[k];
                    if (curNode.m_quantizedAabbMin[k] > rightChildNode.m_quantizedAabbMin[k])
                        curNode.m_quantizedAabbMin[k] = rightChildNode.m_quantizedAabbMin[k];

                    curNode.m_quantizedAabbMax[k] = leftChildNode.m_quantizedAabbMax[k];
                    if (curNode.m_quantizedAabbMax[k] < rightChildNode.m_quantizedAabbMax[k])
                        curNode.m_quantizedAabbMax[k] = rightChildNode.m_quantizedAabbMax[k];
                }
            }
        }

        if (curNodeSubPart >= 0) meshInterface.unLockReadOnlyVertexBase(curNodeSubPart);
    }
}
