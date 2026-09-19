// Port of BulletCollision/BroadphaseCollision/btDbvt.h (Bullet 2.82), struct btDbvtNode
// (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

/**
 * Tree node, 88 bytes in the binary ({@code btAlignedAlloc(sizeof(btDbvtNode)=0x58, 16)}).
 *
 * <p>The C++ union {@code { btDbvtNode* childs[2]; void* data; int dataAsInt; }} is three Java
 * fields. The mapping keeps the observable behaviour of the union:
 *
 * <ul>
 *   <li>{@link #childs}{@code [1] == null} is the leaf test, as in C++.
 *   <li>{@link #data} is what was passed to {@code createnode} (for leaves: the user pointer, e.g.
 *       the btDbvtProxy). {@code createnode} also stores it into {@code childs[0]} when it is a
 *       node, and into {@link #dataAsInt} when it is an {@link Integer} (btCompoundShape passes
 *       child indices as {@code (void*)index}; wrap them with {@code Integer.valueOf}).
 *   <li>Code that writes {@code node->dataAsInt} (btCompoundShape) must write {@link #dataAsInt};
 *       code that reads {@code dataAsInt} (btCompoundCollisionAlgorithm) reads {@link #dataAsInt}.
 *   <li>Reading {@code data} of an internal node returns {@code childs[0]} in C++; the only linked
 *       reader is btDbvt::clone, which does that explicitly.
 * </ul>
 *
 * <p>{@link #addr} is the emulated heap address used by the PTR-ORDER site in btDbvt's {@code
 * sort()} (see docs/re-bullet/pointer-order.md).
 */
public class btDbvtNode {
    public final btDbvtAabbMm volume = new btDbvtAabbMm();
    public btDbvtNode parent;

    public final btDbvtNode[] childs = new btDbvtNode[2];
    public Object data;
    public int dataAsInt;

    /** Emulated address of this node (not a C++ field). PTR-ORDER. */
    public long addr;

    public boolean isleaf() {
        return (childs[1] == null);
    }

    public boolean isinternal() {
        return (!isleaf());
    }
}
