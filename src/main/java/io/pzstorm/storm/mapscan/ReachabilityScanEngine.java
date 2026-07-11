package io.pzstorm.storm.mapscan;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.network.ServerMap;

/**
 * Flood-fill reachability analysis over one window of loaded chunks. Finds walkable squares that
 * cannot be reached from ground level (z=0) — the "sealed rooftop / broken staircase" class of
 * squares where spawned zombies get stuck forever.
 *
 * <p>Runs its own 3D flood fill over {@link IsoGridSquare} data rather than using IsoRegions,
 * because IsoRegions assigns floorless "air" squares to regions and never connects z-layers, so its
 * regions do not model what a zombie can walk. Semantics used here:
 *
 * <ul>
 *   <li><b>Standable</b>: {@link IsoGridSquare#TreatAsSolidFloor()} (solid floor, stairs, or sloped
 *       surface) — the same predicate the native spawn path uses.
 *   <li><b>Lateral edge</b>: orthogonal same-z step blocked only by {@link
 *       IsoGridSquare#isWallTo(IsoGridSquare)} or stair-side entry ({@link
 *       IsoGridSquare#isStairBlockedTo(IsoGridSquare)}). Doors and windows are treated as passable
 *       because zombies can eventually break through them; an area whose only exit is a door is not
 *       a trap.
 *   <li><b>z link</b>: a square holding a top stair segment ({@code stairsTN}/{@code stairsTW})
 *       connects to the landing one square in the stair direction at z+1 — exactly the rule the
 *       high-level pathfinder ({@code HLAStar}/{@code HLStaircase}) uses, so a staircase whose top
 *       landing has no floor (the broken-stair trap) produces no link. Sloped surfaces with height
 *       max ≥ 1.0 link the same way (mirrors {@code HLChunkLevel.initSlopedSurfaces}).
 * </ul>
 *
 * <p>Must run on the server main thread: it reads live {@link IsoGridSquare} state that the main
 * loop mutates every tick.
 */
public final class ReachabilityScanEngine {

    /** Squares per chunk side. */
    public static final int CHUNK_DIM = 8;

    private static final int[] DIR_DX = {0, 0, -1, 1};
    private static final int[] DIR_DY = {-1, 1, 0, 0};

    private ReachabilityScanEngine() {}

    /** Scans the given inclusive chunk rect. Chunks not resident are treated as unseen contacts. */
    public static WindowScanResult scan(
            int minChunkX, int minChunkY, int maxChunkX, int maxChunkY) {
        int chunksW = maxChunkX - minChunkX + 1;
        int chunksH = maxChunkY - minChunkY + 1;
        int w = chunksW * CHUNK_DIM;
        int h = chunksH * CHUNK_DIM;
        int minX = minChunkX * CHUNK_DIM;
        int minY = minChunkY * CHUNK_DIM;

        IsoChunk[] chunks = new IsoChunk[chunksW * chunksH];
        BitSet loadedChunks = new BitSet(chunksW * chunksH);
        int zMin = 0;
        int zMax = 0;
        boolean anyLoaded = false;
        for (int cy = 0; cy < chunksH; cy++) {
            for (int cx = 0; cx < chunksW; cx++) {
                IsoChunk chunk = ServerMap.instance.getChunk(minChunkX + cx, minChunkY + cy);
                if (chunk == null) {
                    continue;
                }
                chunks[cy * chunksW + cx] = chunk;
                loadedChunks.set(cy * chunksW + cx);
                anyLoaded = true;
                zMin = Math.min(zMin, chunk.getMinLevel());
                zMax = Math.max(zMax, chunk.getMaxLevel());
            }
        }
        if (!anyLoaded) {
            return WindowScanResult.empty(minChunkX, minChunkY, maxChunkX, maxChunkY);
        }

        int layers = zMax - zMin + 1;
        int volume = w * h * layers;
        IsoGridSquare[] squares = new IsoGridSquare[volume];
        BitSet standable = new BitSet(volume);
        // z-transition edges (stairs/slopes), keyed by window index; values are window indices
        HashMap<Integer, ArrayList<Integer>> zLinks = new HashMap<>();
        // stair/slope links leaving the window or entering an unloaded chunk, keyed by source index
        HashMap<Integer, ArrayList<Long>> externalZContacts = new HashMap<>();

        int standableCount = 0;
        for (int cy = 0; cy < chunksH; cy++) {
            for (int cx = 0; cx < chunksW; cx++) {
                IsoChunk chunk = chunks[cy * chunksW + cx];
                if (chunk == null) {
                    continue;
                }
                for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
                    for (int sy = 0; sy < CHUNK_DIM; sy++) {
                        for (int sx = 0; sx < CHUNK_DIM; sx++) {
                            IsoGridSquare sq = chunk.getGridSquare(sx, sy, z);
                            if (sq == null) {
                                continue;
                            }
                            int x = cx * CHUNK_DIM + sx;
                            int y = cy * CHUNK_DIM + sy;
                            int idx = ((z - zMin) * h + y) * w + x;
                            squares[idx] = sq;
                            if (sq.TreatAsSolidFloor()) {
                                standable.set(idx);
                                standableCount++;
                            }
                            collectZLinks(
                                    sq,
                                    x,
                                    y,
                                    z,
                                    idx,
                                    w,
                                    h,
                                    zMin,
                                    zMax,
                                    minChunkX,
                                    minChunkY,
                                    loadedChunks,
                                    chunksW,
                                    chunksH,
                                    zLinks,
                                    externalZContacts);
                        }
                    }
                }
            }
        }

        // BFS from all ground-level standable squares
        BitSet reached = new BitSet(volume);
        int[] stack = new int[Math.max(16, standableCount)];
        int sp = 0;
        if (zMin <= 0 && zMax >= 0) {
            int zeroBase = (0 - zMin) * h * w;
            for (int i = 0; i < w * h; i++) {
                int idx = zeroBase + i;
                if (standable.get(idx)) {
                    reached.set(idx);
                    stack[sp++] = idx;
                }
            }
        }
        int reachedCount = sp;
        while (sp > 0) {
            int idx = stack[--sp];
            int x = idx % w;
            int y = (idx / w) % h;
            IsoGridSquare sq = squares[idx];
            for (int d = 0; d < 4; d++) {
                int nx = x + DIR_DX[d];
                int ny = y + DIR_DY[d];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                    continue;
                }
                int nidx = idx + DIR_DX[d] + DIR_DY[d] * w;
                if (!standable.get(nidx) || reached.get(nidx)) {
                    continue;
                }
                if (isPassable(sq, squares[nidx])) {
                    reached.set(nidx);
                    reachedCount++;
                    stack[sp++] = nidx;
                }
            }
            ArrayList<Integer> links = zLinks.get(idx);
            if (links != null) {
                for (int i = 0; i < links.size(); i++) {
                    int nidx = links.get(i);
                    if (standable.get(nidx) && !reached.get(nidx)) {
                        reached.set(nidx);
                        reachedCount++;
                        stack[sp++] = nidx;
                    }
                }
            }
        }

        // Candidate components: standable, not reached
        BitSet candidate = (BitSet) standable.clone();
        candidate.andNot(reached);
        ArrayList<CandidateComponent> components = new ArrayList<>();
        BitSet visited = new BitSet(volume);
        for (int start = candidate.nextSetBit(0);
                start >= 0;
                start = candidate.nextSetBit(start + 1)) {
            if (visited.get(start)) {
                continue;
            }
            components.add(
                    collectComponent(
                            start,
                            candidate,
                            visited,
                            squares,
                            zLinks,
                            externalZContacts,
                            stack,
                            w,
                            h,
                            zMin,
                            minX,
                            minY,
                            minChunkX,
                            minChunkY,
                            chunksW,
                            chunksH,
                            loadedChunks));
        }

        return new WindowScanResult(
                minChunkX,
                minChunkY,
                maxChunkX,
                maxChunkY,
                zMin,
                zMax,
                loadedChunks,
                candidate,
                components,
                standableCount,
                reachedCount);
    }

    private static void collectZLinks(
            IsoGridSquare sq,
            int x,
            int y,
            int z,
            int idx,
            int w,
            int h,
            int zMin,
            int zMax,
            int minChunkX,
            int minChunkY,
            BitSet loadedChunks,
            int chunksW,
            int chunksH,
            HashMap<Integer, ArrayList<Integer>> zLinks,
            HashMap<Integer, ArrayList<Long>> externalZContacts) {
        if (sq.HasStairTopNorth()) {
            addZLink(
                    idx,
                    x,
                    y - 1,
                    z + 1,
                    w,
                    h,
                    zMin,
                    zMax,
                    minChunkX,
                    minChunkY,
                    loadedChunks,
                    chunksW,
                    chunksH,
                    zLinks,
                    externalZContacts);
        }
        if (sq.HasStairTopWest()) {
            addZLink(
                    idx,
                    x - 1,
                    y,
                    z + 1,
                    w,
                    h,
                    zMin,
                    zMax,
                    minChunkX,
                    minChunkY,
                    loadedChunks,
                    chunksW,
                    chunksH,
                    zLinks,
                    externalZContacts);
        }
        IsoDirections slope = sq.getSlopedSurfaceDirection();
        if (slope != null && sq.getSlopedSurfaceHeightMax() >= 1.0F) {
            addZLink(
                    idx,
                    x + slope.dx(),
                    y + slope.dy(),
                    z + 1,
                    w,
                    h,
                    zMin,
                    zMax,
                    minChunkX,
                    minChunkY,
                    loadedChunks,
                    chunksW,
                    chunksH,
                    zLinks,
                    externalZContacts);
        }
    }

    private static void addZLink(
            int fromIdx,
            int tx,
            int ty,
            int tz,
            int w,
            int h,
            int zMin,
            int zMax,
            int minChunkX,
            int minChunkY,
            BitSet loadedChunks,
            int chunksW,
            int chunksH,
            HashMap<Integer, ArrayList<Integer>> zLinks,
            HashMap<Integer, ArrayList<Long>> externalZContacts) {
        boolean inside = tx >= 0 && tx < w && ty >= 0 && ty < h && tz >= zMin && tz <= zMax;
        if (inside) {
            int tcx = tx / CHUNK_DIM;
            int tcy = ty / CHUNK_DIM;
            if (loadedChunks.get(tcy * chunksW + tcx)) {
                int toIdx = ((tz - zMin) * h + ty) * w + tx;
                zLinks.computeIfAbsent(fromIdx, k -> new ArrayList<>()).add(toIdx);
                zLinks.computeIfAbsent(toIdx, k -> new ArrayList<>()).add(fromIdx);
                return;
            }
        }
        if (tx >= 0 && tx < w && ty >= 0 && ty < h && (tz < zMin || tz > zMax)) {
            // a stair to a z-level where no squares exist anywhere: leads nowhere, not a contact
            return;
        }
        int chunkX = minChunkX + Math.floorDiv(tx, CHUNK_DIM);
        int chunkY = minChunkY + Math.floorDiv(ty, CHUNK_DIM);
        externalZContacts
                .computeIfAbsent(fromIdx, k -> new ArrayList<>())
                .add(CandidateComponent.packChunk(chunkX, chunkY));
    }

    private static CandidateComponent collectComponent(
            int start,
            BitSet candidate,
            BitSet visited,
            IsoGridSquare[] squares,
            HashMap<Integer, ArrayList<Integer>> zLinks,
            HashMap<Integer, ArrayList<Long>> externalZContacts,
            int[] stack,
            int w,
            int h,
            int zMin,
            int minX,
            int minY,
            int minChunkX,
            int minChunkY,
            int chunksW,
            int chunksH,
            BitSet loadedChunks) {
        ArrayList<Long> members = new ArrayList<>();
        HashSet<Long> contacts = new HashSet<>();
        boolean hasGround = false;
        int sp = 0;
        stack[sp++] = start;
        visited.set(start);
        while (sp > 0) {
            int idx = stack[--sp];
            int x = idx % w;
            int y = (idx / w) % h;
            int z = idx / (w * h) + zMin;
            members.add(SquareCoord.pack(minX + x, minY + y, z));
            if (z == 0) {
                hasGround = true;
            }
            IsoGridSquare sq = squares[idx];
            for (int d = 0; d < 4; d++) {
                int nx = x + DIR_DX[d];
                int ny = y + DIR_DY[d];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                    contacts.add(
                            CandidateComponent.packChunk(
                                    minChunkX + Math.floorDiv(nx, CHUNK_DIM),
                                    minChunkY + Math.floorDiv(ny, CHUNK_DIM)));
                    continue;
                }
                int ncx = nx / CHUNK_DIM;
                int ncy = ny / CHUNK_DIM;
                if (!loadedChunks.get(ncy * chunksW + ncx)) {
                    contacts.add(CandidateComponent.packChunk(minChunkX + ncx, minChunkY + ncy));
                    continue;
                }
                int nidx = idx + DIR_DX[d] + DIR_DY[d] * w;
                if (!candidate.get(nidx) || visited.get(nidx)) {
                    continue;
                }
                if (isPassable(sq, squares[nidx])) {
                    visited.set(nidx);
                    stack[sp++] = nidx;
                }
            }
            ArrayList<Integer> links = zLinks.get(idx);
            if (links != null) {
                for (int i = 0; i < links.size(); i++) {
                    int nidx = links.get(i);
                    if (candidate.get(nidx) && !visited.get(nidx)) {
                        visited.set(nidx);
                        stack[sp++] = nidx;
                    }
                }
            }
            ArrayList<Long> external = externalZContacts.get(idx);
            if (external != null) {
                contacts.addAll(external);
            }
        }
        long[] squaresArr = new long[members.size()];
        for (int i = 0; i < members.size(); i++) {
            squaresArr[i] = members.get(i);
        }
        long[] contactsArr = new long[contacts.size()];
        int i = 0;
        for (Long c : contacts) {
            contactsArr[i++] = c;
        }
        return new CandidateComponent(squaresArr, hasGround, contactsArr);
    }

    /**
     * A zombie can step between these adjacent same-z squares. Walls block; doors and windows do
     * not (zombies break through); sideways entry onto a stair-top square is blocked, matching
     * {@link IsoGridSquare#isStairBlockedTo(IsoGridSquare)}.
     */
    private static boolean isPassable(IsoGridSquare from, IsoGridSquare to) {
        return !from.isWallTo(to) && !from.isStairBlockedTo(to) && !to.isStairBlockedTo(from);
    }
}
