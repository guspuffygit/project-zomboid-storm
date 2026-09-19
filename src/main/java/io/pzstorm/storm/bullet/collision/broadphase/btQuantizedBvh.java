// Port of BulletCollision/BroadphaseCollision/btQuantizedBvh.h and btQuantizedBvh.cpp
// (Bullet 2.82), class btQuantizedBvh.
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The btQuantizedBvh class stores an AABB tree that can be quickly traversed on CPU and Cell SPU.
 * It is used by the btBvhTriangleMeshShape as midphase.
 *
 * <p>Porting notes:
 *
 * <ul>
 *   <li>{@code unsigned short*} quantized vectors are {@code int[3]} (values 0..65535), optionally
 *       with an offset.
 *   <li>Node pointer arithmetic ({@code rootNode++}, {@code rootNode += escapeIndex}) is index
 *       arithmetic on the contiguous-node arrays.
 *   <li>{@code quantize} reproduces GCC's double to {@code unsigned short} conversion: {@code
 *       cvttsd2si} into a 32-bit register, then the low 16 bits (see {@link #toUShort(double)}).
 *   <li>Serialization ({@code serialize} x2, {@code deSerializeInPlace}, {@code deSerializeFloat},
 *       {@code deSerializeDouble}, the placement copy constructor {@code
 *       btQuantizedBvh(btQuantizedBvh&, bool)}, {@code calculateSerializeBufferSizeNew}) is linked
 *       but is only reachable through btSerializer, which the linearmath port does not provide. Not
 *       ported; see docs/re-bullet/broadphase.md.
 *   <li>{@code walkRecursiveQuantizedTreeAgainstQuantizedTree} is declared but never defined in
 *       2.82.
 * </ul>
 */
public class btQuantizedBvh {
    // enum btTraversalMode
    public static final int TRAVERSAL_STACKLESS = 0;
    public static final int TRAVERSAL_STACKLESS_CACHE_FRIENDLY = 1;
    public static final int TRAVERSAL_RECURSIVE = 2;

    /** Note: currently we have 16 bytes per quantized node */
    public static final int MAX_SUBTREE_SIZE_IN_BYTES = 2048;

    /**
     * 10 gives the potential for 1024 parts, with at most 2^21 (2097152) (minus one actually)
     * triangles each (since the sign bit is reserved
     */
    public static final int MAX_NUM_PARTS_IN_BITS = 10;

    /** sizeof(btQuantizedBvh) in the binary (double precision, x86-64). */
    public static final int SIZEOF = 296;

    public final btVector3 m_bvhAabbMin = new btVector3();
    public final btVector3 m_bvhAabbMax = new btVector3();
    public final btVector3 m_bvhQuantization = new btVector3();

    /** for serialization versioning. It could also be used to detect endianess. */
    public int m_bulletVersion;

    public int m_curNodeIndex;
    // quantization data
    public boolean m_useQuantization;

    public final btAlignedObjectArray<btOptimizedBvhNode> m_leafNodes = newNodeArray();
    public final btAlignedObjectArray<btOptimizedBvhNode> m_contiguousNodes = newNodeArray();
    public final btAlignedObjectArray<btQuantizedBvhNode> m_quantizedLeafNodes =
            newQuantizedNodeArray();
    public final btAlignedObjectArray<btQuantizedBvhNode> m_quantizedContiguousNodes =
            newQuantizedNodeArray();

    public int m_traversalMode;
    public final btAlignedObjectArray<btBvhSubtreeInfo> m_SubtreeHeaders = newSubtreeInfoArray();

    /**
     * This is only used for serialization so we don't have to add serialization directly to
     * btAlignedObjectArray
     */
    public int m_subtreeHeaderCount;

    /** {@code typedef btAlignedObjectArray<btOptimizedBvhNode> NodeArray} */
    public static btAlignedObjectArray<btOptimizedBvhNode> newNodeArray() {
        return new btAlignedObjectArray<btOptimizedBvhNode>(
                () -> new btOptimizedBvhNode(), (dst, src) -> dst.assign(src));
    }

    /** {@code typedef btAlignedObjectArray<btQuantizedBvhNode> QuantizedNodeArray} */
    public static btAlignedObjectArray<btQuantizedBvhNode> newQuantizedNodeArray() {
        return new btAlignedObjectArray<btQuantizedBvhNode>(
                () -> new btQuantizedBvhNode(), (dst, src) -> dst.assign(src));
    }

    /** {@code typedef btAlignedObjectArray<btBvhSubtreeInfo> BvhSubtreeInfoArray} */
    public static btAlignedObjectArray<btBvhSubtreeInfo> newSubtreeInfoArray() {
        return new btAlignedObjectArray<btBvhSubtreeInfo>(
                () -> new btBvhSubtreeInfo(), (dst, src) -> dst.assign(src));
    }

    public btQuantizedBvh() {
        m_bulletVersion = btScalar.BT_BULLET_VERSION;
        m_useQuantization = false;
        // m_traversalMode(TRAVERSAL_STACKLESS_CACHE_FRIENDLY)
        m_traversalMode = TRAVERSAL_STACKLESS;
        // m_traversalMode(TRAVERSAL_RECURSIVE)
        m_subtreeHeaderCount = 0; // PCK: add this line
        m_bvhAabbMin.setValue(
                -btScalar.SIMD_INFINITY, -btScalar.SIMD_INFINITY, -btScalar.SIMD_INFINITY);
        m_bvhAabbMax.setValue(
                btScalar.SIMD_INFINITY, btScalar.SIMD_INFINITY, btScalar.SIMD_INFINITY);
    }

    /**
     * {@code virtual ~btQuantizedBvh()}: empty body, then the member arrays are destroyed in
     * reverse declaration order. Subclasses override and call {@code super.destroy()} last.
     */
    public void destroy() {
        m_SubtreeHeaders.clear();
        m_quantizedContiguousNodes.clear();
        m_quantizedLeafNodes.clear();
        m_contiguousNodes.clear();
        m_leafNodes.clear();
    }

    // ---------------------------------------------------------------------------------------
    // header inlines
    // ---------------------------------------------------------------------------------------

    /**
     * two versions, one for quantized and normal nodes. This allows code-reuse while maintaining
     * readability (no template/macro!) this might be refactored into a virtual, it is usually not
     * calculated at run-time
     */
    public void setInternalNodeAabbMin(int nodeIndex, btVector3 aabbMin) {
        if (m_useQuantization) {
            quantize(m_quantizedContiguousNodes.get(nodeIndex).m_quantizedAabbMin, aabbMin, 0);
        } else {
            m_contiguousNodes.get(nodeIndex).m_aabbMinOrg.set(aabbMin);
        }
    }

    public void setInternalNodeAabbMax(int nodeIndex, btVector3 aabbMax) {
        if (m_useQuantization) {
            quantize(m_quantizedContiguousNodes.get(nodeIndex).m_quantizedAabbMax, aabbMax, 1);
        } else {
            m_contiguousNodes.get(nodeIndex).m_aabbMaxOrg.set(aabbMax);
        }
    }

    /** Returns a new vector (C++ returns by value). */
    public btVector3 getAabbMin(int nodeIndex) {
        if (m_useQuantization) {
            return unQuantize(m_quantizedLeafNodes.get(nodeIndex).m_quantizedAabbMin);
        }
        // non-quantized
        return new btVector3(m_leafNodes.get(nodeIndex).m_aabbMinOrg);
    }

    /** Returns a new vector (C++ returns by value). */
    public btVector3 getAabbMax(int nodeIndex) {
        if (m_useQuantization) {
            return unQuantize(m_quantizedLeafNodes.get(nodeIndex).m_quantizedAabbMax);
        }
        // non-quantized
        return new btVector3(m_leafNodes.get(nodeIndex).m_aabbMaxOrg);
    }

    public void setInternalNodeEscapeIndex(int nodeIndex, int escapeIndex) {
        if (m_useQuantization) {
            m_quantizedContiguousNodes.get(nodeIndex).m_escapeIndexOrTriangleIndex = -escapeIndex;
        } else {
            m_contiguousNodes.get(nodeIndex).m_escapeIndex = escapeIndex;
        }
    }

    public void mergeInternalNodeAabb(int nodeIndex, btVector3 newAabbMin, btVector3 newAabbMax) {
        if (m_useQuantization) {
            int[] quantizedAabbMin = new int[3];
            int[] quantizedAabbMax = new int[3];
            quantize(quantizedAabbMin, newAabbMin, 0);
            quantize(quantizedAabbMax, newAabbMax, 1);
            btQuantizedBvhNode node = m_quantizedContiguousNodes.get(nodeIndex);
            for (int i = 0; i < 3; i++) {
                if (node.m_quantizedAabbMin[i] > quantizedAabbMin[i])
                    node.m_quantizedAabbMin[i] = quantizedAabbMin[i];

                if (node.m_quantizedAabbMax[i] < quantizedAabbMax[i])
                    node.m_quantizedAabbMax[i] = quantizedAabbMax[i];
            }
        } else {
            // non-quantized
            m_contiguousNodes.get(nodeIndex).m_aabbMinOrg.setMin(newAabbMin);
            m_contiguousNodes.get(nodeIndex).m_aabbMaxOrg.setMax(newAabbMax);
        }
    }

    /**
     * {@code (unsigned short)d} as GCC -O3 emits it on x86-64: {@code cvttsd2si} to a 32-bit
     * register and keep the low 16 bits (NaN / out of range gives 0x80000000, low half 0).
     */
    public static int toUShort(double d) {
        return btScalar.cvttsd2si(d) & 0xffff;
    }

    public void quantize(int[] out, btVector3 point, int isMax) {
        quantize(out, 0, point, isMax);
    }

    /** {@code quantize(unsigned short* out, ...)} with {@code out = &arr[off]}. */
    public void quantize(int[] out, int off, btVector3 point, int isMax) {
        btVector3 v = (point.sub(m_bvhAabbMin)).mul(m_bvhQuantization);
        /// Make sure rounding is done in a way that unQuantize(quantizeWithClamp(...)) is
        // conservative
        /// end-points always set the first bit, so that they are sorted properly (so that
        /// neighbouring AABBs overlap properly)
        /// @todo: double-check this
        if (isMax != 0) {
            out[off] = ((toUShort(v.getX() + 1.0) | 1)) & 0xffff;
            out[off + 1] = ((toUShort(v.getY() + 1.0) | 1)) & 0xffff;
            out[off + 2] = ((toUShort(v.getZ() + 1.0) | 1)) & 0xffff;
        } else {
            out[off] = ((toUShort(v.getX()) & 0xfffe)) & 0xffff;
            out[off + 1] = ((toUShort(v.getY()) & 0xfffe)) & 0xffff;
            out[off + 2] = ((toUShort(v.getZ()) & 0xfffe)) & 0xffff;
        }
    }

    public void quantizeWithClamp(int[] out, btVector3 point2, int isMax) {
        btVector3 clampedPoint = new btVector3(point2);
        clampedPoint.setMax(m_bvhAabbMin);
        clampedPoint.setMin(m_bvhAabbMax);

        quantize(out, clampedPoint, isMax);
    }

    public btVector3 unQuantize(int[] vecIn) {
        return unQuantize(vecIn, 0);
    }

    public btVector3 unQuantize(int[] vecIn, int off) {
        btVector3 vecOut = new btVector3();
        vecOut.setValue(
                (double) (vecIn[off]) / (m_bvhQuantization.getX()),
                (double) (vecIn[off + 1]) / (m_bvhQuantization.getY()),
                (double) (vecIn[off + 2]) / (m_bvhQuantization.getZ()));
        vecOut.addLocal(m_bvhAabbMin);
        return vecOut;
    }

    /**
     * setTraversalMode let's you choose between stackless, recursive or stackless cache friendly
     * tree traversal. Note this is only implemented for quantized trees.
     */
    public void setTraversalMode(int traversalMode) {
        m_traversalMode = traversalMode;
    }

    public btAlignedObjectArray<btQuantizedBvhNode> getLeafNodeArray() {
        return m_quantizedLeafNodes;
    }

    public btAlignedObjectArray<btQuantizedBvhNode> getQuantizedNodeArray() {
        return m_quantizedContiguousNodes;
    }

    public btAlignedObjectArray<btBvhSubtreeInfo> getSubtreeInfoArray() {
        return m_SubtreeHeaders;
    }

    public boolean isQuantized() {
        return m_useQuantization;
    }

    // ---------------------------------------------------------------------------------------
    // btQuantizedBvh.cpp
    // ---------------------------------------------------------------------------------------

    /**
     * buildInternal is expert use only: assumes that setQuantizationValues and LeafNodeArray are
     * initialized
     */
    public void buildInternal() {
        /// assumes that caller filled in the m_quantizedLeafNodes
        m_useQuantization = true;
        int numLeafNodes = 0;

        if (m_useQuantization) {
            // now we have an array of leafnodes in m_leafNodes
            numLeafNodes = m_quantizedLeafNodes.size();

            m_quantizedContiguousNodes.resize(2 * numLeafNodes);
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

    public void setQuantizationValues(btVector3 bvhAabbMin, btVector3 bvhAabbMax) {
        setQuantizationValues(bvhAabbMin, bvhAabbMax, 1.0);
    }

    public void setQuantizationValues(
            btVector3 bvhAabbMin, btVector3 bvhAabbMax, double quantizationMargin) {
        // enlarge the AABB to avoid division by zero when initializing the quantization values
        btVector3 clampValue =
                new btVector3(quantizationMargin, quantizationMargin, quantizationMargin);
        m_bvhAabbMin.set(bvhAabbMin.sub(clampValue));
        m_bvhAabbMax.set(bvhAabbMax.add(clampValue));
        btVector3 aabbSize = m_bvhAabbMax.sub(m_bvhAabbMin);
        m_bvhQuantization.set(new btVector3(65533.0, 65533.0, 65533.0).div(aabbSize));

        m_useQuantization = true;

        {
            int[] vecIn = new int[3];
            btVector3 v;
            {
                quantize(vecIn, m_bvhAabbMin, 0);
                v = unQuantize(vecIn);
                m_bvhAabbMin.setMin(v.sub(clampValue));
            }
            {
                quantize(vecIn, m_bvhAabbMax, 1);
                v = unQuantize(vecIn);
                m_bvhAabbMax.setMax(v.add(clampValue));
            }
            aabbSize = m_bvhAabbMax.sub(m_bvhAabbMin);
            m_bvhQuantization.set(new btVector3(65533.0, 65533.0, 65533.0).div(aabbSize));
        }
    }

    public void buildTree(int startIndex, int endIndex) {
        int splitAxis, splitIndex, i;
        int numIndices = endIndex - startIndex;
        int curIndex = m_curNodeIndex;

        if (numIndices == 1) {
            assignInternalNodeFromLeafNode(m_curNodeIndex, startIndex);

            m_curNodeIndex++;
            return;
        }
        // calculate Best Splitting Axis and where to split it. Sort the incoming 'leafNodes' array
        // within range 'startIndex/endIndex'.

        splitAxis = calcSplittingAxis(startIndex, endIndex);

        splitIndex = sortAndCalcSplittingIndex(startIndex, endIndex, splitAxis);

        int internalNodeIndex = m_curNodeIndex;

        // set the min aabb to 'inf' or a max value, and set the max aabb to a -inf/minimum value.
        // the aabb will be expanded during buildTree/mergeInternalNodeAabb with actual node values
        setInternalNodeAabbMin(
                m_curNodeIndex,
                m_bvhAabbMax); // can't use btVector3(SIMD_INFINITY,...) because of quantization
        setInternalNodeAabbMax(
                m_curNodeIndex,
                m_bvhAabbMin); // can't use btVector3(-SIMD_INFINITY,...) because of quantization

        for (i = startIndex; i < endIndex; i++) {
            mergeInternalNodeAabb(m_curNodeIndex, getAabbMin(i), getAabbMax(i));
        }

        m_curNodeIndex++;

        // internalNode->m_escapeIndex;

        int leftChildNodexIndex = m_curNodeIndex;

        // build left child tree
        buildTree(startIndex, splitIndex);

        int rightChildNodexIndex = m_curNodeIndex;
        // build right child tree
        buildTree(splitIndex, endIndex);

        int escapeIndex = m_curNodeIndex - curIndex;

        if (m_useQuantization) {
            // escapeIndex is the number of nodes of this subtree
            final int sizeQuantizedNode = btQuantizedBvhNode.SIZEOF;
            final int treeSizeInBytes = escapeIndex * sizeQuantizedNode;
            if (treeSizeInBytes > MAX_SUBTREE_SIZE_IN_BYTES) {
                updateSubtreeHeaders(leftChildNodexIndex, rightChildNodexIndex);
            }
        } else {

        }

        setInternalNodeEscapeIndex(internalNodeIndex, escapeIndex);
    }

    public void updateSubtreeHeaders(int leftChildNodexIndex, int rightChildNodexIndex) {
        btQuantizedBvhNode leftChildNode = m_quantizedContiguousNodes.get(leftChildNodexIndex);
        int leftSubTreeSize = leftChildNode.isLeafNode() ? 1 : leftChildNode.getEscapeIndex();
        int leftSubTreeSizeInBytes = leftSubTreeSize * btQuantizedBvhNode.SIZEOF;

        btQuantizedBvhNode rightChildNode = m_quantizedContiguousNodes.get(rightChildNodexIndex);
        int rightSubTreeSize = rightChildNode.isLeafNode() ? 1 : rightChildNode.getEscapeIndex();
        int rightSubTreeSizeInBytes = rightSubTreeSize * btQuantizedBvhNode.SIZEOF;

        if (leftSubTreeSizeInBytes <= MAX_SUBTREE_SIZE_IN_BYTES) {
            btBvhSubtreeInfo subtree = m_SubtreeHeaders.expand();
            subtree.setAabbFromQuantizeNode(leftChildNode);
            subtree.m_rootNodeIndex = leftChildNodexIndex;
            subtree.m_subtreeSize = leftSubTreeSize;
        }

        if (rightSubTreeSizeInBytes <= MAX_SUBTREE_SIZE_IN_BYTES) {
            btBvhSubtreeInfo subtree = m_SubtreeHeaders.expand();
            subtree.setAabbFromQuantizeNode(rightChildNode);
            subtree.m_rootNodeIndex = rightChildNodexIndex;
            subtree.m_subtreeSize = rightSubTreeSize;
        }

        // PCK: update the copy of the size
        m_subtreeHeaderCount = m_SubtreeHeaders.size();
    }

    public int sortAndCalcSplittingIndex(int startIndex, int endIndex, int splitAxis) {
        int i;
        int splitIndex = startIndex;
        int numIndices = endIndex - startIndex;
        double splitValue;

        btVector3 means = new btVector3(0.0, 0.0, 0.0);
        for (i = startIndex; i < endIndex; i++) {
            btVector3 center = (getAabbMax(i).add(getAabbMin(i))).mul(0.5);
            means.addLocal(center);
        }
        means.mulLocal(1.0 / (double) numIndices);

        splitValue = means.get(splitAxis);

        // sort leafNodes so all values larger then splitValue comes first, and smaller values start
        // from 'splitIndex'.
        for (i = startIndex; i < endIndex; i++) {
            btVector3 center = (getAabbMax(i).add(getAabbMin(i))).mul(0.5);
            if (center.get(splitAxis) > splitValue) {
                // swap
                swapLeafNodes(i, splitIndex);
                splitIndex++;
            }
        }

        // if the splitIndex causes unbalanced trees, fix this by using the center in between
        // startIndex and endIndex otherwise the tree-building might fail due to stack-overflows in
        // certain cases.

        // this should be safe too:
        int rangeBalancedIndices = numIndices / 3;
        boolean unbalanced =
                ((splitIndex <= (startIndex + rangeBalancedIndices))
                        || (splitIndex >= (endIndex - 1 - rangeBalancedIndices)));

        if (unbalanced) {
            splitIndex = startIndex + (numIndices >> 1);
        }

        return splitIndex;
    }

    public int calcSplittingAxis(int startIndex, int endIndex) {
        int i;

        btVector3 means = new btVector3(0.0, 0.0, 0.0);
        btVector3 variance = new btVector3(0.0, 0.0, 0.0);
        int numIndices = endIndex - startIndex;

        for (i = startIndex; i < endIndex; i++) {
            btVector3 center = (getAabbMax(i).add(getAabbMin(i))).mul(0.5);
            means.addLocal(center);
        }
        means.mulLocal(1.0 / (double) numIndices);

        for (i = startIndex; i < endIndex; i++) {
            btVector3 center = (getAabbMax(i).add(getAabbMin(i))).mul(0.5);
            btVector3 diff2 = center.sub(means);
            diff2 = diff2.mul(diff2);
            variance.addLocal(diff2);
        }
        variance.mulLocal(1.0 / ((double) numIndices - 1));

        return variance.maxAxis();
    }

    public void reportAabbOverlappingNodex(
            btNodeOverlapCallback nodeCallback, btVector3 aabbMin, btVector3 aabbMax) {
        // either choose recursive traversal (walkTree) or stackless (walkStacklessTree)

        if (m_useQuantization) {
            /// quantize query AABB
            int[] quantizedQueryAabbMin = new int[3];
            int[] quantizedQueryAabbMax = new int[3];
            quantizeWithClamp(quantizedQueryAabbMin, aabbMin, 0);
            quantizeWithClamp(quantizedQueryAabbMax, aabbMax, 1);

            switch (m_traversalMode) {
                case TRAVERSAL_STACKLESS:
                    walkStacklessQuantizedTree(
                            nodeCallback,
                            quantizedQueryAabbMin,
                            quantizedQueryAabbMax,
                            0,
                            m_curNodeIndex);
                    break;
                case TRAVERSAL_STACKLESS_CACHE_FRIENDLY:
                    walkStacklessQuantizedTreeCacheFriendly(
                            nodeCallback, quantizedQueryAabbMin, quantizedQueryAabbMax);
                    break;
                case TRAVERSAL_RECURSIVE:
                    {
                        walkRecursiveQuantizedTreeAgainstQueryAabb(
                                0, nodeCallback, quantizedQueryAabbMin, quantizedQueryAabbMax);
                    }
                    break;
                default:
                    // unsupported
            }
        } else {
            walkStacklessTree(nodeCallback, aabbMin, aabbMax);
        }
    }

    public void walkStacklessTree(
            btNodeOverlapCallback nodeCallback, btVector3 aabbMin, btVector3 aabbMax) {
        int rootNode = 0; // &m_contiguousNodes[0]
        int escapeIndex, curIndex = 0;
        int walkIterations = 0;
        boolean isLeafNode;
        // PCK: unsigned instead of bool
        boolean aabbOverlap;

        while (curIndex < m_curNodeIndex) {
            walkIterations++;
            btOptimizedBvhNode node = m_contiguousNodes.get(rootNode);
            aabbOverlap =
                    btAabbUtil2.TestAabbAgainstAabb2(
                            aabbMin, aabbMax, node.m_aabbMinOrg, node.m_aabbMaxOrg);
            isLeafNode = node.m_escapeIndex == -1;

            // PCK: unsigned instead of bool
            if (isLeafNode && aabbOverlap) {
                nodeCallback.processNode(node.m_subPart, node.m_triangleIndex);
            }

            // PCK: unsigned instead of bool
            if (aabbOverlap || isLeafNode) {
                rootNode++;
                curIndex++;
            } else {
                escapeIndex = node.m_escapeIndex;
                rootNode += escapeIndex;
                curIndex += escapeIndex;
            }
        }
        if (btGlobals.maxIterations < walkIterations) btGlobals.maxIterations = walkIterations;
    }

    /** {@code currentNode} is an index into m_quantizedContiguousNodes. */
    public void walkRecursiveQuantizedTreeAgainstQueryAabb(
            int currentNode,
            btNodeOverlapCallback nodeCallback,
            int[] quantizedQueryAabbMin,
            int[] quantizedQueryAabbMax) {
        boolean isLeafNode;
        // PCK: unsigned instead of bool
        int aabbOverlap;

        btQuantizedBvhNode cur = m_quantizedContiguousNodes.get(currentNode);
        // PCK: unsigned instead of bool
        aabbOverlap =
                btAabbUtil2.testQuantizedAabbAgainstQuantizedAabb(
                        quantizedQueryAabbMin,
                        quantizedQueryAabbMax,
                        cur.m_quantizedAabbMin,
                        cur.m_quantizedAabbMax);
        isLeafNode = cur.isLeafNode();

        // PCK: unsigned instead of bool
        if (aabbOverlap != 0) {
            if (isLeafNode) {
                nodeCallback.processNode(cur.getPartId(), cur.getTriangleIndex());
            } else {
                // process left and right children
                int leftChildNode = currentNode + 1;
                walkRecursiveQuantizedTreeAgainstQueryAabb(
                        leftChildNode, nodeCallback, quantizedQueryAabbMin, quantizedQueryAabbMax);

                btQuantizedBvhNode left = m_quantizedContiguousNodes.get(leftChildNode);
                int rightChildNode =
                        left.isLeafNode()
                                ? leftChildNode + 1
                                : leftChildNode + left.getEscapeIndex();
                walkRecursiveQuantizedTreeAgainstQueryAabb(
                        rightChildNode, nodeCallback, quantizedQueryAabbMin, quantizedQueryAabbMax);
            }
        }
    }

    public void walkStacklessTreeAgainstRay(
            btNodeOverlapCallback nodeCallback,
            btVector3 raySource,
            btVector3 rayTarget,
            btVector3 aabbMin,
            btVector3 aabbMax,
            int startNodeIndex,
            int endNodeIndex) {
        int rootNode = 0; // &m_contiguousNodes[0]
        int escapeIndex, curIndex = 0;
        int walkIterations = 0;
        boolean isLeafNode;
        // PCK: unsigned instead of bool
        boolean aabbOverlap = false;
        boolean rayBoxOverlap = false;
        double lambda_max = 1.0;

        /* Quick pruning by quantized box */
        btVector3 rayAabbMin = new btVector3(raySource);
        btVector3 rayAabbMax = new btVector3(raySource);
        rayAabbMin.setMin(rayTarget);
        rayAabbMax.setMax(rayTarget);

        /* Add box cast extents to bounding box */
        rayAabbMin.addLocal(aabbMin);
        rayAabbMax.addLocal(aabbMax);

        // RAYAABB2
        btVector3 rayDir = (rayTarget.sub(raySource));
        rayDir.normalize();
        lambda_max = rayDir.dot(rayTarget.sub(raySource));
        /// what about division by zero? --> just set rayDirection[i] to 1.0
        btVector3 rayDirectionInverse = new btVector3();
        rayDirectionInverse.set(
                0, rayDir.get(0) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(0));
        rayDirectionInverse.set(
                1, rayDir.get(1) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(1));
        rayDirectionInverse.set(
                2, rayDir.get(2) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(2));
        int[] sign = {
            rayDirectionInverse.get(0) < 0.0 ? 1 : 0,
            rayDirectionInverse.get(1) < 0.0 ? 1 : 0,
            rayDirectionInverse.get(2) < 0.0 ? 1 : 0
        };

        btVector3[] bounds = {new btVector3(), new btVector3()};
        double[] param = new double[1];

        while (curIndex < m_curNodeIndex) {
            param[0] = 1.0;

            walkIterations++;

            btOptimizedBvhNode node = m_contiguousNodes.get(rootNode);
            bounds[0].set(node.m_aabbMinOrg);
            bounds[1].set(node.m_aabbMaxOrg);
            /* Add box cast extents */
            bounds[0].subLocal(aabbMax);
            bounds[1].subLocal(aabbMin);

            aabbOverlap =
                    btAabbUtil2.TestAabbAgainstAabb2(
                            rayAabbMin, rayAabbMax, node.m_aabbMinOrg, node.m_aabbMaxOrg);
            // perhaps profile if it is worth doing the aabbOverlap test first

            /// careful with this check: need to check division by zero (above) and fix the
            /// unQuantize method
            rayBoxOverlap =
                    aabbOverlap
                            ? btAabbUtil2.btRayAabb2(
                                    raySource,
                                    rayDirectionInverse,
                                    sign,
                                    bounds,
                                    param,
                                    0.0,
                                    lambda_max)
                            : false;

            isLeafNode = node.m_escapeIndex == -1;

            // PCK: unsigned instead of bool
            if (isLeafNode && rayBoxOverlap) {
                nodeCallback.processNode(node.m_subPart, node.m_triangleIndex);
            }

            // PCK: unsigned instead of bool
            if (rayBoxOverlap || isLeafNode) {
                rootNode++;
                curIndex++;
            } else {
                escapeIndex = node.m_escapeIndex;
                rootNode += escapeIndex;
                curIndex += escapeIndex;
            }
        }
        if (btGlobals.maxIterations < walkIterations) btGlobals.maxIterations = walkIterations;
    }

    public void walkStacklessQuantizedTreeAgainstRay(
            btNodeOverlapCallback nodeCallback,
            btVector3 raySource,
            btVector3 rayTarget,
            btVector3 aabbMin,
            btVector3 aabbMax,
            int startNodeIndex,
            int endNodeIndex) {
        int curIndex = startNodeIndex;
        int walkIterations = 0;

        int rootNode = startNodeIndex; // &m_quantizedContiguousNodes[startNodeIndex]
        int escapeIndex;

        boolean isLeafNode;
        // PCK: unsigned instead of bool
        int boxBoxOverlap = 0;
        boolean rayBoxOverlap = false;

        double lambda_max = 1.0;

        // RAYAABB2
        btVector3 rayDirection = (rayTarget.sub(raySource));
        rayDirection.normalize();
        lambda_max = rayDirection.dot(rayTarget.sub(raySource));
        /// what about division by zero? --> just set rayDirection[i] to 1.0
        rayDirection.set(
                0,
                rayDirection.get(0) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDirection.get(0));
        rayDirection.set(
                1,
                rayDirection.get(1) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDirection.get(1));
        rayDirection.set(
                2,
                rayDirection.get(2) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDirection.get(2));
        int[] sign = {
            rayDirection.get(0) < 0.0 ? 1 : 0,
            rayDirection.get(1) < 0.0 ? 1 : 0,
            rayDirection.get(2) < 0.0 ? 1 : 0
        };

        /* Quick pruning by quantized box */
        btVector3 rayAabbMin = new btVector3(raySource);
        btVector3 rayAabbMax = new btVector3(raySource);
        rayAabbMin.setMin(rayTarget);
        rayAabbMax.setMax(rayTarget);

        /* Add box cast extents to bounding box */
        rayAabbMin.addLocal(aabbMin);
        rayAabbMax.addLocal(aabbMax);

        int[] quantizedQueryAabbMin = new int[3];
        int[] quantizedQueryAabbMax = new int[3];
        quantizeWithClamp(quantizedQueryAabbMin, rayAabbMin, 0);
        quantizeWithClamp(quantizedQueryAabbMax, rayAabbMax, 1);

        btVector3[] bounds = {new btVector3(), new btVector3()};
        double[] param = new double[1];

        while (curIndex < endNodeIndex) {
            walkIterations++;
            // PCK: unsigned instead of bool
            // only interested if this is closer than any previous hit
            param[0] = 1.0;
            rayBoxOverlap = false;
            btQuantizedBvhNode node = m_quantizedContiguousNodes.get(rootNode);
            boxBoxOverlap =
                    btAabbUtil2.testQuantizedAabbAgainstQuantizedAabb(
                            quantizedQueryAabbMin,
                            quantizedQueryAabbMax,
                            node.m_quantizedAabbMin,
                            node.m_quantizedAabbMax);
            isLeafNode = node.isLeafNode();
            if (boxBoxOverlap != 0) {
                bounds[0].set(unQuantize(node.m_quantizedAabbMin));
                bounds[1].set(unQuantize(node.m_quantizedAabbMax));
                /* Add box cast extents */
                bounds[0].subLocal(aabbMax);
                bounds[1].subLocal(aabbMin);
                /// careful with this check: need to check division by zero (above) and fix the
                /// unQuantize method

                rayBoxOverlap =
                        btAabbUtil2.btRayAabb2(
                                raySource, rayDirection, sign, bounds, param, 0.0, lambda_max);
            }

            if (isLeafNode && rayBoxOverlap) {
                nodeCallback.processNode(node.getPartId(), node.getTriangleIndex());
            }

            // PCK: unsigned instead of bool
            if (rayBoxOverlap || isLeafNode) {
                rootNode++;
                curIndex++;
            } else {
                escapeIndex = node.getEscapeIndex();
                rootNode += escapeIndex;
                curIndex += escapeIndex;
            }
        }
        if (btGlobals.maxIterations < walkIterations) btGlobals.maxIterations = walkIterations;
    }

    public void walkStacklessQuantizedTree(
            btNodeOverlapCallback nodeCallback,
            int[] quantizedQueryAabbMin,
            int[] quantizedQueryAabbMax,
            int startNodeIndex,
            int endNodeIndex) {
        int curIndex = startNodeIndex;
        int walkIterations = 0;

        int rootNode = startNodeIndex; // &m_quantizedContiguousNodes[startNodeIndex]
        int escapeIndex;

        boolean isLeafNode;
        // PCK: unsigned instead of bool
        int aabbOverlap;

        while (curIndex < endNodeIndex) {
            walkIterations++;
            btQuantizedBvhNode node = m_quantizedContiguousNodes.get(rootNode);
            // PCK: unsigned instead of bool
            aabbOverlap =
                    btAabbUtil2.testQuantizedAabbAgainstQuantizedAabb(
                            quantizedQueryAabbMin,
                            quantizedQueryAabbMax,
                            node.m_quantizedAabbMin,
                            node.m_quantizedAabbMax);
            isLeafNode = node.isLeafNode();

            if (isLeafNode && aabbOverlap != 0) {
                nodeCallback.processNode(node.getPartId(), node.getTriangleIndex());
            }

            // PCK: unsigned instead of bool
            if ((aabbOverlap != 0) || isLeafNode) {
                rootNode++;
                curIndex++;
            } else {
                escapeIndex = node.getEscapeIndex();
                rootNode += escapeIndex;
                curIndex += escapeIndex;
            }
        }
        if (btGlobals.maxIterations < walkIterations) btGlobals.maxIterations = walkIterations;
    }

    /** This traversal can be called from Playstation 3 SPU */
    public void walkStacklessQuantizedTreeCacheFriendly(
            btNodeOverlapCallback nodeCallback,
            int[] quantizedQueryAabbMin,
            int[] quantizedQueryAabbMax) {
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
                walkStacklessQuantizedTree(
                        nodeCallback,
                        quantizedQueryAabbMin,
                        quantizedQueryAabbMax,
                        subtree.m_rootNodeIndex,
                        subtree.m_rootNodeIndex + subtree.m_subtreeSize);
            }
        }
    }

    public void reportRayOverlappingNodex(
            btNodeOverlapCallback nodeCallback, btVector3 raySource, btVector3 rayTarget) {
        reportBoxCastOverlappingNodex(
                nodeCallback, raySource, rayTarget, new btVector3(0, 0, 0), new btVector3(0, 0, 0));
    }

    public void reportBoxCastOverlappingNodex(
            btNodeOverlapCallback nodeCallback,
            btVector3 raySource,
            btVector3 rayTarget,
            btVector3 aabbMin,
            btVector3 aabbMax) {
        // always use stackless

        if (m_useQuantization) {
            walkStacklessQuantizedTreeAgainstRay(
                    nodeCallback, raySource, rayTarget, aabbMin, aabbMax, 0, m_curNodeIndex);
        } else {
            walkStacklessTreeAgainstRay(
                    nodeCallback, raySource, rayTarget, aabbMin, aabbMax, 0, m_curNodeIndex);
        }
    }

    public void swapLeafNodes(int i, int splitIndex) {
        if (m_useQuantization) {
            btQuantizedBvhNode tmp = new btQuantizedBvhNode().assign(m_quantizedLeafNodes.get(i));
            m_quantizedLeafNodes.get(i).assign(m_quantizedLeafNodes.get(splitIndex));
            m_quantizedLeafNodes.get(splitIndex).assign(tmp);
        } else {
            btOptimizedBvhNode tmp = new btOptimizedBvhNode().assign(m_leafNodes.get(i));
            m_leafNodes.get(i).assign(m_leafNodes.get(splitIndex));
            m_leafNodes.get(splitIndex).assign(tmp);
        }
    }

    public void assignInternalNodeFromLeafNode(int internalNode, int leafNodeIndex) {
        if (m_useQuantization) {
            m_quantizedContiguousNodes
                    .get(internalNode)
                    .assign(m_quantizedLeafNodes.get(leafNodeIndex));
        } else {
            m_contiguousNodes.get(internalNode).assign(m_leafNodes.get(leafNodeIndex));
        }
    }

    public static int getAlignmentSerializationPadding() {
        // I changed this to 0 since the extra padding is not needed or used.
        return 0; // BVH_ALIGNMENT_BLOCKS * BVH_ALIGNMENT;
    }

    /** Calculate space needed to store BVH for serialization (unsigned; sizes from the binary). */
    public int calculateSerializeBufferSize() {
        int baseSize = SIZEOF + getAlignmentSerializationPadding();
        baseSize += btBvhSubtreeInfo.SIZEOF * m_subtreeHeaderCount;
        if (m_useQuantization) {
            return baseSize + m_curNodeIndex * btQuantizedBvhNode.SIZEOF;
        }
        return baseSize + m_curNodeIndex * btOptimizedBvhNode.SIZEOF;
    }
}
