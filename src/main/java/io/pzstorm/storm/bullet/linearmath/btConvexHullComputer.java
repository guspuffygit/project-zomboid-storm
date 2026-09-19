// Port of LinearMath/btConvexHullComputer.h / .cpp (Bullet 2.82), BT_USE_DOUBLE_PRECISION.
package io.pzstorm.storm.bullet.linearmath;

import io.pzstorm.storm.bullet.linearmath.btConvexHullInternal.CoordSource;
import io.pzstorm.storm.bullet.linearmath.btConvexHullInternal.Vertex;

/**
 * Convex hull (Preparata-Hong, Ole Kniemeyer). Output is in {@link #vertices}, {@link #edges},
 * {@link #faces}; edge and face order match C++ exactly.
 *
 * <pre>
 * C++                                               Java
 * ------------------------------------------------  ------------------------------------------------
 * compute(const float* coords, stride, count, ...)  compute(float[] coords, strideBytes, count, ...)
 * compute(const double* coords, stride, count, ...) compute(double[] coords, strideBytes, count, ...)
 * compute(&btVector3[0].x(), sizeof(btVector3), ..) compute(btVector3[] / btAlignedObjectArray, ...)
 * const Edge* e = &edges[i]                         edges.get(i)
 * e->getNextEdgeOfVertex() (pointer arithmetic)     e.getNextEdgeOfVertex() (same slot object)
 * </pre>
 *
 * <p>{@code stride} is in bytes as in C++ and must be a multiple of the element size. {@link Edge}
 * keeps a back-reference to its owning array and its index so that the C++ {@code this + next}
 * pointer arithmetic resolves to the same slot object (identity comparisons like {@code edge !=
 * firstEdge} keep working).
 */
public class btConvexHullComputer {

    public static class Edge {
        int next;
        int reverse;
        int targetVertex;

        /** Owning array + slot index (stands in for {@code this}). */
        btAlignedObjectArray<Edge> m_owner;

        int m_index;

        public Edge() {}

        /** Implicit copy-assignment: the three ints only. */
        public Edge set(Edge o) {
            next = o.next;
            reverse = o.reverse;
            targetVertex = o.targetVertex;
            return this;
        }

        private Edge at(int off) {
            return m_owner.get(m_index + off);
        }

        public int getSourceVertex() {
            return at(reverse).targetVertex;
        }

        public int getTargetVertex() {
            return targetVertex;
        }

        /** Clockwise list of all edges of a vertex. */
        public Edge getNextEdgeOfVertex() {
            return at(next);
        }

        /** Counter-clockwise list of all edges of a face. */
        public Edge getNextEdgeOfFace() {
            return at(reverse).getNextEdgeOfVertex();
        }

        public Edge getReverseEdge() {
            return at(reverse);
        }

        /** Raw C++ fields (private in C++; exposed read-only for tests/debugging). */
        public int getNextOffset() {
            return next;
        }

        public int getReverseOffset() {
            return reverse;
        }
    }

    /** Vertices of the output hull. */
    public final btAlignedObjectArray<btVector3> vertices = btAlignedObjectArray.ofVector3();

    /** Edges of the output hull. */
    public final btAlignedObjectArray<Edge> edges =
            new btAlignedObjectArray<>(Edge::new, (d, s) -> d.set(s));

    /** Faces: each entry is an index into {@link #edges} pointing to an edge of the face. */
    public final btIntArray faces = new btIntArray();

    /** {@code compute(const float* coords, int stride, int count, shrink, shrinkClamp)} */
    public double compute(
            float[] coords, int stride, int count, double shrink, double shrinkClamp) {
        int s = stride / 4;
        return compute((i, k) -> (double) coords[i * s + k], count, shrink, shrinkClamp);
    }

    /** {@code compute(const double* coords, int stride, int count, shrink, shrinkClamp)} */
    public double compute(
            double[] coords, int stride, int count, double shrink, double shrinkClamp) {
        int s = stride / 8;
        return compute((i, k) -> coords[i * s + k], count, shrink, shrinkClamp);
    }

    /** {@code compute(&points[0].getX(), sizeof(btVector3), count, ...)} */
    public double compute(btVector3[] points, int count, double shrink, double shrinkClamp) {
        return compute((i, k) -> points[i].get(k), count, shrink, shrinkClamp);
    }

    /** {@code compute(&points[0].getX(), sizeof(btVector3), count, ...)} */
    public double compute(
            btAlignedObjectArray<btVector3> points, int count, double shrink, double shrinkClamp) {
        return compute((i, k) -> points.get(i).get(k), count, shrink, shrinkClamp);
    }

    private static int getVertexCopy(Vertex vertex, btAlignedObjectArray<Vertex> vertices) {
        int index = vertex.copy;
        if (index < 0) {
            index = vertices.size();
            vertex.copy = index;
            vertices.push_back(vertex);
        }
        return index;
    }

    private Edge pushEdge() {
        int s = edges.size();
        edges.push_back(new Edge());
        Edge c = edges.get(s);
        c.m_owner = edges;
        c.m_index = s;
        return c;
    }

    /** The private {@code compute(const void*, bool doubleCoords, int stride, int count, ...)}. */
    public double compute(CoordSource coords, int count, double shrink, double shrinkClamp) {
        if (count <= 0) {
            vertices.clear();
            edges.clear();
            faces.clear();
            return 0;
        }

        btConvexHullInternal hull = new btConvexHullInternal();
        try {
            hull.compute(coords, count);

            double shift = 0;
            if ((shrink > 0) && ((shift = hull.shrink(shrink, shrinkClamp)) < 0)) {
                vertices.clear();
                edges.clear();
                faces.clear();
                return shift;
            }

            vertices.resize(0);
            edges.resize(0);
            faces.resize(0);

            btAlignedObjectArray<Vertex> oldVertices = new btAlignedObjectArray<>();
            getVertexCopy(hull.vertexList, oldVertices);
            int copied = 0;
            while (copied < oldVertices.size()) {
                Vertex v = oldVertices.get(copied);
                vertices.push_back(hull.getCoordinates(v));
                btConvexHullInternal.Edge firstEdge = v.edges;
                if (firstEdge != null) {
                    int firstCopy = -1;
                    int prevCopy = -1;
                    btConvexHullInternal.Edge e = firstEdge;
                    do {
                        if (e.copy < 0) {
                            int s = edges.size();
                            Edge c = pushEdge();
                            Edge r = pushEdge();
                            e.copy = s;
                            e.reverse.copy = s + 1;
                            c.reverse = 1;
                            r.reverse = -1;
                            c.targetVertex = getVertexCopy(e.target, oldVertices);
                            r.targetVertex = copied;
                        }
                        if (prevCopy >= 0) {
                            edges.get(e.copy).next = prevCopy - e.copy;
                        } else {
                            firstCopy = e.copy;
                        }
                        prevCopy = e.copy;
                        e = e.next;
                    } while (e != firstEdge);
                    edges.get(firstCopy).next = prevCopy - firstCopy;
                }
                copied++;
            }

            for (int i = 0; i < copied; i++) {
                Vertex v = oldVertices.get(i);
                btConvexHullInternal.Edge firstEdge = v.edges;
                if (firstEdge != null) {
                    btConvexHullInternal.Edge e = firstEdge;
                    do {
                        if (e.copy >= 0) {
                            faces.push_back(e.copy);
                            btConvexHullInternal.Edge f = e;
                            do {
                                f.copy = -1;
                                f = f.reverse.prev;
                            } while (f != e);
                        }
                        e = e.next;
                    } while (e != firstEdge);
                }
            }

            oldVertices.clear();
            return shift;
        } finally {
            hull.destruct();
        }
    }
}
