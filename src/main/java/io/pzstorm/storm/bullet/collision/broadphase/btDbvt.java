// Port of BulletCollision/BroadphaseCollision/btDbvt.cpp and btDbvt.h (Bullet 2.82), struct
// btDbvt, its file-static helpers and the templates the binary instantiates (enumNodes,
// collideTTpersistentStack, collideTV, rayTestInternal, rayTest).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayDeque;

/**
 * The btDbvt class implements a fast dynamic bounding volume tree based on axis aligned bounding
 * boxes (aabb tree). This btDbvt is used for soft body collision detection and for the
 * btDbvtBroadphase. It has a fast insert, remove and update of nodes. Unlike the btQuantizedBvh,
 * nodes can be dynamically moved around, which allows for change in topology of the underlying data
 * structure.
 *
 * <p>Node addresses ({@link btDbvtNode#addr}) are emulated because {@code sort()} compares node
 * pointers (PTR-ORDER). Freed node addresses are reused LIFO the way glibc's tcache/fastbins reuse
 * 0x80-byte chunks (btAlignedAlloc(88,16) is malloc(111)); see {@link #allocNodeAddress()}.
 *
 * <p>Unlinked templates not ported: collideTT, collideTU, collideKDOP, collideOCL, enumLeaves,
 * nearest, allocate. benchmark() is the empty inline version.
 */
public class btDbvt {
    // ---- Stack elements ----

    public static class sStkNN {
        public btDbvtNode a;
        public btDbvtNode b;

        public sStkNN() {}

        public sStkNN(btDbvtNode na, btDbvtNode nb) {
            a = na;
            b = nb;
        }

        public sStkNN set(sStkNN o) {
            a = o.a;
            b = o.b;
            return this;
        }
    }

    public static class sStkNP {
        public btDbvtNode node;
        public int mask;

        public sStkNP(btDbvtNode n, int m) {
            node = n;
            mask = m;
        }
    }

    public static class sStkNPS {
        public btDbvtNode node;
        public int mask;
        public double value;

        public sStkNPS() {}

        public sStkNPS(btDbvtNode n, int m, double v) {
            node = n;
            mask = m;
            value = v;
        }
    }

    public static class sStkCLN {
        public btDbvtNode node;
        public btDbvtNode parent;

        public sStkCLN() {}

        public sStkCLN(btDbvtNode n, btDbvtNode p) {
            node = n;
            parent = p;
        }

        public sStkCLN set(sStkCLN o) {
            node = o.node;
            parent = o.parent;
            return this;
        }
    }

    // ---- Policies/Interfaces ----

    /** ICollide (DBVT_VIRTUAL is virtual in this build: BT_USE_SSE is off). */
    public abstract static class ICollide {
        public void destroy() {}

        public void Process(btDbvtNode a, btDbvtNode b) {}

        public void Process(btDbvtNode n) {}

        public void Process(btDbvtNode n, double s) {
            Process(n);
        }

        public boolean Descent(btDbvtNode n) {
            return (true);
        }

        public boolean AllLeaves(btDbvtNode n) {
            return (true);
        }
    }

    public abstract static class IWriter {
        public void destroy() {}

        public abstract void Prepare(btDbvtNode root, int numnodes);

        public abstract void WriteNode(btDbvtNode n, int index, int parent, int child0, int child1);

        public abstract void WriteLeaf(btDbvtNode n, int index, int parent);
    }

    public abstract static class IClone {
        public void destroy() {}

        public void CloneLeaf(btDbvtNode n) {}
    }

    // ---- Constants ----
    public static final int SIMPLE_STACKSIZE = 64;
    public static final int DOUBLE_STACKSIZE = SIMPLE_STACKSIZE * 2;

    /** sizeof(btDbvtNode) in the binary. */
    public static final int SIZEOF_NODE = 88;

    // ---- Fields ----
    public btDbvtNode m_root;
    public btDbvtNode m_free;
    public int m_lkhd;
    public int m_leaves;

    /** unsigned in C++; only used via {@code >>>} and {@code ++}. */
    public int m_opath;

    public final btAlignedObjectArray<sStkNN> m_stkStack =
            new btAlignedObjectArray<>(() -> new sStkNN(), (d, s) -> d.set(s));
    public final btAlignedObjectArray<btDbvtNode> m_rayTestStack = new btAlignedObjectArray<>();

    // ---- Emulated heap for nodes (not C++) ----

    private static final int TCACHE_COUNT = 7;

    /** glibc tcache bin for 0x80 chunks (LIFO, at most 7 entries). */
    private static final ArrayDeque<Long> tcache = new ArrayDeque<>();

    /** glibc fastbin for 0x80 chunks (LIFO). */
    private static final ArrayDeque<Long> fastbin = new ArrayDeque<>();

    static {
        btGlobals.addressResetHooks.add(btDbvt::resetNodeAddressPool);
    }

    /** Drops every freed node address (run by btGlobals.resetAddresses()). */
    public static synchronized void resetNodeAddressPool() {
        tcache.clear();
        fastbin.clear();
    }

    /**
     * {@code btAlignedAlloc(sizeof(btDbvtNode),16)}: counts the allocation and returns a reused
     * address when one of the same chunk class was freed (tcache first, then fastbin with the glibc
     * stash of the remaining fastbin chunks into tcache), else a fresh one. Other allocations that
     * share this chunk class, and chunk splitting/consolidation, are not modeled.
     */
    public static synchronized long allocNodeAddress() {
        if (!tcache.isEmpty()) {
            btGlobals.gNumAlignedAllocs++;
            return tcache.pop();
        }
        if (!fastbin.isEmpty()) {
            btGlobals.gNumAlignedAllocs++;
            long victim = fastbin.pop();
            while (tcache.size() < TCACHE_COUNT && !fastbin.isEmpty()) {
                tcache.push(fastbin.pop());
            }
            return victim;
        }
        return btGlobals.btAlignedAlloc(SIZEOF_NODE, 16);
    }

    /** {@code btAlignedFree(node)}: counts the free and returns the address to the bins. */
    public static synchronized void freeNodeAddress(btDbvtNode node) {
        if (node == null) {
            btGlobals.btAlignedFree(0);
            return;
        }
        btGlobals.btAlignedFree(node.addr);
        if (tcache.size() < TCACHE_COUNT) tcache.push(node.addr);
        else fastbin.push(node.addr);
    }

    // ---- file-static helpers (btDbvt.cpp) ----

    /** btDbvtNodeEnumerator */
    private static final class btDbvtNodeEnumerator extends ICollide {
        final btAlignedObjectArray<btDbvtNode> nodes = new btAlignedObjectArray<>();

        @Override
        public void Process(btDbvtNode n) {
            nodes.push_back(n);
        }
    }

    static int indexof(btDbvtNode node) {
        return (node.parent.childs[1] == node) ? 1 : 0;
    }

    static btDbvtAabbMm merge(btDbvtAabbMm a, btDbvtAabbMm b) {
        btDbvtAabbMm res = new btDbvtAabbMm();
        btDbvtAabbMm.Merge(a, b, res);
        return (res);
    }

    /** volume+edge lengths */
    static double size(btDbvtAabbMm a) {
        final btVector3 edges = a.Lengths();
        return (edges.x() * edges.y() * edges.z() + edges.x() + edges.y() + edges.z());
    }

    static void getmaxdepth(btDbvtNode node, int depth, int[] maxdepth) {
        if (node.isinternal()) {
            getmaxdepth(node.childs[0], depth + 1, maxdepth);
            getmaxdepth(node.childs[1], depth + 1, maxdepth);
        } else maxdepth[0] = Math.max(maxdepth[0], depth);
    }

    static void deletenode(btDbvt pdbvt, btDbvtNode node) {
        freeNodeAddress(pdbvt.m_free);
        pdbvt.m_free = node;
    }

    static void recursedeletenode(btDbvt pdbvt, btDbvtNode node) {
        if (!node.isleaf()) {
            recursedeletenode(pdbvt, node.childs[0]);
            recursedeletenode(pdbvt, node.childs[1]);
        }
        if (node == pdbvt.m_root) pdbvt.m_root = null;
        deletenode(pdbvt, node);
    }

    /**
     * Writes the union {@code data} (see btDbvtNode): childs[0] aliases a node pointer, dataAsInt
     * aliases an integer payload.
     */
    static btDbvtNode createnode(btDbvt pdbvt, btDbvtNode parent, Object data) {
        btDbvtNode node;
        if (pdbvt.m_free != null) {
            node = pdbvt.m_free;
            pdbvt.m_free = null;
        } else {
            node = new btDbvtNode();
            node.addr = allocNodeAddress();
        }
        node.parent = parent;
        node.data = data;
        node.childs[0] = (data instanceof btDbvtNode) ? (btDbvtNode) data : null;
        node.dataAsInt = (data instanceof Integer) ? (Integer) data : 0;
        node.childs[1] = null;
        return (node);
    }

    static btDbvtNode createnode(
            btDbvt pdbvt, btDbvtNode parent, btDbvtAabbMm volume, Object data) {
        btDbvtNode node = createnode(pdbvt, parent, data);
        node.volume.set(volume);
        return (node);
    }

    static btDbvtNode createnode(
            btDbvt pdbvt,
            btDbvtNode parent,
            btDbvtAabbMm volume0,
            btDbvtAabbMm volume1,
            Object data) {
        btDbvtNode node = createnode(pdbvt, parent, data);
        btDbvtAabbMm.Merge(volume0, volume1, node.volume);
        return (node);
    }

    static void insertleaf(btDbvt pdbvt, btDbvtNode root, btDbvtNode leaf) {
        if (pdbvt.m_root == null) {
            pdbvt.m_root = leaf;
            leaf.parent = null;
        } else {
            if (!root.isleaf()) {
                do {
                    root =
                            root.childs[
                                    btDbvtAabbMm.Select(
                                            leaf.volume,
                                            root.childs[0].volume,
                                            root.childs[1].volume)];
                } while (!root.isleaf());
            }
            btDbvtNode prev = root.parent;
            btDbvtNode node = createnode(pdbvt, prev, leaf.volume, root.volume, null);
            if (prev != null) {
                prev.childs[indexof(root)] = node;
                node.childs[0] = root;
                root.parent = node;
                node.childs[1] = leaf;
                leaf.parent = node;
                do {
                    if (!prev.volume.Contain(node.volume))
                        btDbvtAabbMm.Merge(
                                prev.childs[0].volume, prev.childs[1].volume, prev.volume);
                    else break;
                    node = prev;
                } while (null != (prev = node.parent));
            } else {
                node.childs[0] = root;
                root.parent = node;
                node.childs[1] = leaf;
                leaf.parent = node;
                pdbvt.m_root = node;
            }
        }
    }

    static btDbvtNode removeleaf(btDbvt pdbvt, btDbvtNode leaf) {
        if (leaf == pdbvt.m_root) {
            pdbvt.m_root = null;
            return (null);
        } else {
            btDbvtNode parent = leaf.parent;
            btDbvtNode prev = parent.parent;
            btDbvtNode sibling = parent.childs[1 - indexof(leaf)];
            if (prev != null) {
                prev.childs[indexof(parent)] = sibling;
                sibling.parent = prev;
                deletenode(pdbvt, parent);
                while (prev != null) {
                    final btDbvtAabbMm pb = new btDbvtAabbMm(prev.volume);
                    btDbvtAabbMm.Merge(prev.childs[0].volume, prev.childs[1].volume, prev.volume);
                    if (btDbvtAabbMm.NotEqual(pb, prev.volume)) {
                        prev = prev.parent;
                    } else break;
                }
                return (prev != null ? prev : pdbvt.m_root);
            } else {
                pdbvt.m_root = sibling;
                sibling.parent = null;
                deletenode(pdbvt, parent);
                return (pdbvt.m_root);
            }
        }
    }

    static void fetchleaves(
            btDbvt pdbvt, btDbvtNode root, btAlignedObjectArray<btDbvtNode> leaves) {
        fetchleaves(pdbvt, root, leaves, -1);
    }

    static void fetchleaves(
            btDbvt pdbvt, btDbvtNode root, btAlignedObjectArray<btDbvtNode> leaves, int depth) {
        if (root.isinternal() && depth != 0) {
            fetchleaves(pdbvt, root.childs[0], leaves, depth - 1);
            fetchleaves(pdbvt, root.childs[1], leaves, depth - 1);
            deletenode(pdbvt, root);
        } else {
            leaves.push_back(root);
        }
    }

    static void split(
            btAlignedObjectArray<btDbvtNode> leaves,
            btAlignedObjectArray<btDbvtNode> left,
            btAlignedObjectArray<btDbvtNode> right,
            btVector3 org,
            btVector3 axis) {
        left.resize(0);
        right.resize(0);
        for (int i = 0, ni = leaves.size(); i < ni; ++i) {
            if (axis.dot(leaves.get(i).volume.Center().sub(org)) < 0) left.push_back(leaves.get(i));
            else right.push_back(leaves.get(i));
        }
    }

    static btDbvtAabbMm bounds(btAlignedObjectArray<btDbvtNode> leaves) {
        btDbvtAabbMm volume = new btDbvtAabbMm(leaves.get(0).volume);
        for (int i = 1, ni = leaves.size(); i < ni; ++i) {
            btDbvtAabbMm.Merge(volume, leaves.get(i).volume, volume);
        }
        return (volume);
    }

    static void bottomup(btDbvt pdbvt, btAlignedObjectArray<btDbvtNode> leaves) {
        while (leaves.size() > 1) {
            double minsize = btScalar.SIMD_INFINITY;
            int[] minidx = {-1, -1};
            for (int i = 0; i < leaves.size(); ++i) {
                for (int j = i + 1; j < leaves.size(); ++j) {
                    final double sz = size(merge(leaves.get(i).volume, leaves.get(j).volume));
                    if (sz < minsize) {
                        minsize = sz;
                        minidx[0] = i;
                        minidx[1] = j;
                    }
                }
            }
            btDbvtNode[] n = {leaves.get(minidx[0]), leaves.get(minidx[1])};
            btDbvtNode p = createnode(pdbvt, null, n[0].volume, n[1].volume, null);
            p.childs[0] = n[0];
            p.childs[1] = n[1];
            n[0].parent = p;
            n[1].parent = p;
            leaves.set(minidx[0], p);
            leaves.swap(minidx[1], leaves.size() - 1);
            leaves.pop_back();
        }
    }

    private static final btVector3[] axis = {
        new btVector3(1, 0, 0), new btVector3(0, 1, 0), new btVector3(0, 0, 1)
    };

    static btDbvtNode topdown(
            btDbvt pdbvt, btAlignedObjectArray<btDbvtNode> leaves, int bu_treshold) {
        if (leaves.size() > 1) {
            if (leaves.size() > bu_treshold) {
                final btDbvtAabbMm vol = bounds(leaves);
                final btVector3 org = vol.Center();
                @SuppressWarnings("unchecked")
                btAlignedObjectArray<btDbvtNode>[] sets =
                        new btAlignedObjectArray[] {
                            new btAlignedObjectArray<btDbvtNode>(),
                            new btAlignedObjectArray<btDbvtNode>()
                        };
                int bestaxis = -1;
                int bestmidp = leaves.size();
                int[][] splitcount = {{0, 0}, {0, 0}, {0, 0}};
                int i;
                for (i = 0; i < leaves.size(); ++i) {
                    final btVector3 x = leaves.get(i).volume.Center().sub(org);
                    for (int j = 0; j < 3; ++j) {
                        ++splitcount[j][x.dot(axis[j]) > 0 ? 1 : 0];
                    }
                }
                for (i = 0; i < 3; ++i) {
                    if ((splitcount[i][0] > 0) && (splitcount[i][1] > 0)) {
                        final int midp =
                                (int)
                                        btScalar.btFabs(
                                                (double) (splitcount[i][0] - splitcount[i][1]));
                        if (midp < bestmidp) {
                            bestaxis = i;
                            bestmidp = midp;
                        }
                    }
                }
                if (bestaxis >= 0) {
                    sets[0].reserve(splitcount[bestaxis][0]);
                    sets[1].reserve(splitcount[bestaxis][1]);
                    split(leaves, sets[0], sets[1], org, axis[bestaxis]);
                } else {
                    sets[0].reserve(leaves.size() / 2 + 1);
                    sets[1].reserve(leaves.size() / 2);
                    for (int k = 0, ni = leaves.size(); k < ni; ++k) {
                        sets[k & 1].push_back(leaves.get(k));
                    }
                }
                btDbvtNode node = createnode(pdbvt, null, vol, null);
                node.childs[0] = topdown(pdbvt, sets[0], bu_treshold);
                node.childs[1] = topdown(pdbvt, sets[1], bu_treshold);
                node.childs[0].parent = node;
                node.childs[1].parent = node;
                // ~tNodeArray sets[2] (destroyed in reverse order)
                sets[1].clear();
                sets[0].clear();
                return (node);
            } else {
                bottomup(pdbvt, leaves);
                return (leaves.get(0));
            }
        }
        return (leaves.get(0));
    }

    /**
     * {@code sort(btDbvtNode* n, btDbvtNode*& r)}: r is always {@code m_root}, so the reference is
     * written to {@code pdbvt.m_root}.
     */
    static btDbvtNode sort(btDbvtNode n, btDbvt pdbvt) {
        btDbvtNode p = n.parent;
        // PTR-ORDER: C++ compares the node pointers (p > n; a null parent is 0 and never greater).
        if (p != null && Long.compareUnsigned(p.addr, n.addr) > 0) {
            final int i = indexof(n);
            final int j = 1 - i;
            btDbvtNode s = p.childs[j];
            btDbvtNode q = p.parent;
            if (q != null) q.childs[indexof(p)] = n;
            else pdbvt.m_root = n;
            s.parent = n;
            p.parent = n;
            n.parent = q;
            p.childs[0] = n.childs[0];
            p.childs[1] = n.childs[1];
            n.childs[0].parent = p;
            n.childs[1].parent = p;
            n.childs[i] = p;
            n.childs[j] = s;
            // btSwap(p->volume, n->volume)
            btDbvtAabbMm tmp = new btDbvtAabbMm(p.volume);
            p.volume.set(n.volume);
            n.volume.set(tmp);
            return (p);
        }
        return (n);
    }

    // ---- Api ----

    public btDbvt() {
        m_root = null;
        m_free = null;
        m_lkhd = -1;
        m_leaves = 0;
        m_opath = 0;
    }

    /** ~btDbvt(): clear(), then m_rayTestStack and m_stkStack are destroyed. */
    public void destroy() {
        clear();
        m_rayTestStack.clear();
        m_stkStack.clear();
    }

    public void clear() {
        if (m_root != null) recursedeletenode(this, m_root);
        freeNodeAddress(m_free);
        m_free = null;
        m_lkhd = -1;
        m_stkStack.clear();
        m_opath = 0;
    }

    public boolean empty() {
        return (null == m_root);
    }

    public void optimizeBottomUp() {
        if (m_root != null) {
            btAlignedObjectArray<btDbvtNode> leaves = new btAlignedObjectArray<>();
            leaves.reserve(m_leaves);
            fetchleaves(this, m_root, leaves);
            bottomup(this, leaves);
            m_root = leaves.get(0);
            leaves.clear();
        }
    }

    public void optimizeTopDown() {
        optimizeTopDown(128);
    }

    public void optimizeTopDown(int bu_treshold) {
        if (m_root != null) {
            btAlignedObjectArray<btDbvtNode> leaves = new btAlignedObjectArray<>();
            leaves.reserve(m_leaves);
            fetchleaves(this, m_root, leaves);
            m_root = topdown(this, leaves, bu_treshold);
            leaves.clear();
        }
    }

    public void optimizeIncremental(int passes) {
        if (passes < 0) passes = m_leaves;
        if (m_root != null && (passes > 0)) {
            do {
                btDbvtNode node = m_root;
                int bit = 0;
                while (node.isinternal()) {
                    node = sort(node, this).childs[(m_opath >>> bit) & 1];
                    bit = (bit + 1) & (4 * 8 - 1);
                }
                update(node);
                ++m_opath;
            } while (--passes != 0);
        }
    }

    public btDbvtNode insert(btDbvtAabbMm volume, Object data) {
        btDbvtNode leaf = createnode(this, null, volume, data);
        insertleaf(this, m_root, leaf);
        ++m_leaves;
        return (leaf);
    }

    public void update(btDbvtNode leaf) {
        update(leaf, -1);
    }

    public void update(btDbvtNode leaf, int lookahead) {
        btDbvtNode root = removeleaf(this, leaf);
        if (root != null) {
            if (lookahead >= 0) {
                for (int i = 0; (i < lookahead) && root.parent != null; ++i) {
                    root = root.parent;
                }
            } else root = m_root;
        }
        insertleaf(this, root, leaf);
    }

    public void update(btDbvtNode leaf, btDbvtAabbMm volume) {
        btDbvtNode root = removeleaf(this, leaf);
        if (root != null) {
            if (m_lkhd >= 0) {
                for (int i = 0; (i < m_lkhd) && root.parent != null; ++i) {
                    root = root.parent;
                }
            } else root = m_root;
        }
        leaf.volume.set(volume);
        insertleaf(this, root, leaf);
    }

    public boolean update(btDbvtNode leaf, btDbvtAabbMm volume, btVector3 velocity, double margin) {
        if (leaf.volume.Contain(volume)) return (false);
        volume.Expand(new btVector3(margin, margin, margin));
        volume.SignedExpand(velocity);
        update(leaf, volume);
        return (true);
    }

    public boolean update(btDbvtNode leaf, btDbvtAabbMm volume, btVector3 velocity) {
        if (leaf.volume.Contain(volume)) return (false);
        volume.SignedExpand(velocity);
        update(leaf, volume);
        return (true);
    }

    public boolean update(btDbvtNode leaf, btDbvtAabbMm volume, double margin) {
        if (leaf.volume.Contain(volume)) return (false);
        volume.Expand(new btVector3(margin, margin, margin));
        update(leaf, volume);
        return (true);
    }

    public void remove(btDbvtNode leaf) {
        removeleaf(this, leaf);
        deletenode(this, leaf);
        --m_leaves;
    }

    public void write(IWriter iwriter) {
        btDbvtNodeEnumerator nodes = new btDbvtNodeEnumerator();
        nodes.nodes.reserve(m_leaves * 2);
        enumNodes(m_root, nodes);
        iwriter.Prepare(m_root, nodes.nodes.size());
        for (int i = 0; i < nodes.nodes.size(); ++i) {
            final btDbvtNode n = nodes.nodes.get(i);
            int p = -1;
            if (n.parent != null) p = nodes.nodes.findLinearSearch(n.parent);
            if (n.isinternal()) {
                final int c0 = nodes.nodes.findLinearSearch(n.childs[0]);
                final int c1 = nodes.nodes.findLinearSearch(n.childs[1]);
                iwriter.WriteNode(n, i, p, c0, c1);
            } else {
                iwriter.WriteLeaf(n, i, p);
            }
        }
        nodes.nodes.clear();
    }

    public void clone(btDbvt dest) {
        clone(dest, null);
    }

    /**
     * Upstream quirk kept: the child slot is chosen by the parity of the stack index ({@code i&1}),
     * which swaps the children of nodes whose children were pushed at an odd index.
     */
    public void clone(btDbvt dest, IClone iclone) {
        dest.clear();
        if (m_root != null) {
            btAlignedObjectArray<sStkCLN> stack =
                    new btAlignedObjectArray<>(() -> new sStkCLN(), (d, s) -> d.set(s));
            stack.reserve(m_leaves);
            stack.push_back(new sStkCLN(m_root, null));
            do {
                final int i = stack.size() - 1;
                final sStkCLN e = new sStkCLN().set(stack.get(i));
                // e.node->data reads the union: childs[0] for internal nodes.
                Object data = e.node.isinternal() ? e.node.childs[0] : e.node.data;
                btDbvtNode n = createnode(dest, e.parent, e.node.volume, data);
                n.dataAsInt = e.node.isinternal() ? 0 : e.node.dataAsInt;
                stack.pop_back();
                if (e.parent != null) e.parent.childs[i & 1] = n;
                else dest.m_root = n;
                if (e.node.isinternal()) {
                    stack.push_back(new sStkCLN(e.node.childs[0], n));
                    stack.push_back(new sStkCLN(e.node.childs[1], n));
                } else {
                    iclone.CloneLeaf(n);
                }
            } while (stack.size() > 0);
            stack.clear();
        }
    }

    public static int maxdepth(btDbvtNode node) {
        int[] depth = {0};
        if (node != null) getmaxdepth(node, 1, depth);
        return (depth[0]);
    }

    public static int countLeaves(btDbvtNode node) {
        if (node.isinternal()) return (countLeaves(node.childs[0]) + countLeaves(node.childs[1]));
        else return (1);
    }

    public static void extractLeaves(btDbvtNode node, btAlignedObjectArray<btDbvtNode> leaves) {
        if (node.isinternal()) {
            extractLeaves(node.childs[0], leaves);
            extractLeaves(node.childs[1], leaves);
        } else {
            leaves.push_back(node);
        }
    }

    public static void benchmark() {}

    // ---- templates (btDbvt.h) ----

    public static void enumNodes(btDbvtNode root, ICollide policy) {
        policy.Process(root);
        if (root.isinternal()) {
            enumNodes(root.childs[0], policy);
            enumNodes(root.childs[1], policy);
        }
    }

    public void collideTTpersistentStack(btDbvtNode root0, btDbvtNode root1, ICollide policy) {
        if (root0 != null && root1 != null) {
            int depth = 1;
            int treshold = DOUBLE_STACKSIZE - 4;

            m_stkStack.resize(DOUBLE_STACKSIZE);
            putNN(0, root0, root1);
            do {
                sStkNN slot = m_stkStack.get(--depth);
                final btDbvtNode pa = slot.a;
                final btDbvtNode pb = slot.b;
                if (depth > treshold) {
                    m_stkStack.resize(m_stkStack.size() * 2);
                    treshold = m_stkStack.size() - 4;
                }
                if (pa == pb) {
                    if (pa.isinternal()) {
                        putNN(depth++, pa.childs[0], pa.childs[0]);
                        putNN(depth++, pa.childs[1], pa.childs[1]);
                        putNN(depth++, pa.childs[0], pa.childs[1]);
                    }
                } else if (btDbvtAabbMm.Intersect(pa.volume, pb.volume)) {
                    if (pa.isinternal()) {
                        if (pb.isinternal()) {
                            putNN(depth++, pa.childs[0], pb.childs[0]);
                            putNN(depth++, pa.childs[1], pb.childs[0]);
                            putNN(depth++, pa.childs[0], pb.childs[1]);
                            putNN(depth++, pa.childs[1], pb.childs[1]);
                        } else {
                            putNN(depth++, pa.childs[0], pb);
                            putNN(depth++, pa.childs[1], pb);
                        }
                    } else {
                        if (pb.isinternal()) {
                            putNN(depth++, pa, pb.childs[0]);
                            putNN(depth++, pa, pb.childs[1]);
                        } else {
                            policy.Process(pa, pb);
                        }
                    }
                }
            } while (depth != 0);
        }
    }

    /** {@code m_stkStack[i]=sStkNN(a,b)} */
    private void putNN(int i, btDbvtNode a, btDbvtNode b) {
        sStkNN s = m_stkStack.get(i);
        s.a = a;
        s.b = b;
    }

    public void collideTV(btDbvtNode root, btDbvtAabbMm vol, ICollide policy) {
        if (root != null) {
            final btDbvtAabbMm volume = new btDbvtAabbMm(vol);
            btAlignedObjectArray<btDbvtNode> stack = new btAlignedObjectArray<>();
            stack.resize(0);
            stack.reserve(SIMPLE_STACKSIZE);
            stack.push_back(root);
            do {
                final btDbvtNode n = stack.get(stack.size() - 1);
                stack.pop_back();
                if (btDbvtAabbMm.Intersect(n.volume, volume)) {
                    if (n.isinternal()) {
                        stack.push_back(n.childs[0]);
                        stack.push_back(n.childs[1]);
                    } else {
                        policy.Process(n);
                    }
                }
            } while (stack.size() > 0);
            stack.clear();
        }
    }

    /**
     * rayTestInternal is faster than rayTest, because it uses a persistent stack (to reduce dynamic
     * memory allocations to a minimum) and it uses precomputed signs/rayInverseDirections.
     * rayTestInternal is used by btDbvtBroadphase to accelerate world ray casts.
     */
    public void rayTestInternal(
            btDbvtNode root,
            btVector3 rayFrom,
            btVector3 rayTo,
            btVector3 rayDirectionInverse,
            int[] signs,
            double lambda_max,
            btVector3 aabbMin,
            btVector3 aabbMax,
            ICollide policy) {
        if (root != null) {
            int depth = 1;
            int treshold = DOUBLE_STACKSIZE - 2;
            btAlignedObjectArray<btDbvtNode> stack = m_rayTestStack;
            stack.resize(DOUBLE_STACKSIZE);
            stack.set(0, root);
            btVector3[] bounds = {new btVector3(), new btVector3()};
            double[] tmin = new double[1];
            do {
                final btDbvtNode node = stack.get(--depth);
                bounds[0].set(node.volume.Mins().sub(aabbMax));
                bounds[1].set(node.volume.Maxs().sub(aabbMin));
                tmin[0] = 1.0;
                double lambda_min = 0.0;
                boolean result1 =
                        btAabbUtil2.btRayAabb2(
                                rayFrom,
                                rayDirectionInverse,
                                signs,
                                bounds,
                                tmin,
                                lambda_min,
                                lambda_max);
                if (result1) {
                    if (node.isinternal()) {
                        if (depth > treshold) {
                            stack.resize(stack.size() * 2);
                            treshold = stack.size() - 2;
                        }
                        stack.set(depth++, node.childs[0]);
                        stack.set(depth++, node.childs[1]);
                    } else {
                        policy.Process(node);
                    }
                }
            } while (depth != 0);
        }
    }

    /**
     * rayTest is slower than rayTestInternal, because it builds a local stack, using memory
     * allocations, and it recomputes signs/rayDirectionInverses each time.
     */
    public static void rayTest(
            btDbvtNode root, btVector3 rayFrom, btVector3 rayTo, ICollide policy) {
        if (root != null) {
            btVector3 rayDir = (rayTo.sub(rayFrom));
            rayDir.normalize();

            // what about division by zero? --> just set rayDirection[i] to INF/BT_LARGE_FLOAT
            btVector3 rayDirectionInverse = new btVector3();
            rayDirectionInverse.set(
                    0, rayDir.get(0) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(0));
            rayDirectionInverse.set(
                    1, rayDir.get(1) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(1));
            rayDirectionInverse.set(
                    2, rayDir.get(2) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(2));
            int[] signs = {
                rayDirectionInverse.get(0) < 0.0 ? 1 : 0,
                rayDirectionInverse.get(1) < 0.0 ? 1 : 0,
                rayDirectionInverse.get(2) < 0.0 ? 1 : 0
            };

            double lambda_max = rayDir.dot(rayTo.sub(rayFrom));

            btAlignedObjectArray<btDbvtNode> stack = new btAlignedObjectArray<>();

            int depth = 1;
            int treshold = DOUBLE_STACKSIZE - 2;

            stack.resize(DOUBLE_STACKSIZE);
            stack.set(0, root);
            btVector3[] bounds = {new btVector3(), new btVector3()};
            double[] tmin = new double[1];
            do {
                final btDbvtNode node = stack.get(--depth);

                bounds[0].set(node.volume.Mins());
                bounds[1].set(node.volume.Maxs());

                tmin[0] = 1.0;
                double lambda_min = 0.0;
                boolean result1 =
                        btAabbUtil2.btRayAabb2(
                                rayFrom,
                                rayDirectionInverse,
                                signs,
                                bounds,
                                tmin,
                                lambda_min,
                                lambda_max);

                if (result1) {
                    if (node.isinternal()) {
                        if (depth > treshold) {
                            stack.resize(stack.size() * 2);
                            treshold = stack.size() - 2;
                        }
                        stack.set(depth++, node.childs[0]);
                        stack.set(depth++, node.childs[1]);
                    } else {
                        policy.Process(node);
                    }
                }
            } while (depth != 0);
            stack.clear();
        }
    }
}
