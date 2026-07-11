package io.pzstorm.storm.mapscan;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Stitches per-window candidate components into global verdicts. Windows tile the scan area with at
 * least one chunk of overlap, so every square pair (and every stair link) is fully evaluated inside
 * at least one window. Rules:
 *
 * <ul>
 *   <li>Candidate squares shared between two windows merge their components (union-find).
 *   <li><b>Poison</b> (proven reachable): a square that one window flags as candidate but another
 *       covering window does not — the other window found a path to ground the first couldn't see.
 *       The whole merged component is reachable, because reachability spreads along it.
 *   <li><b>Fail open</b>: at finalize, a component that touched a chunk never scanned by any window
 *       (and that has lot data) can't be proven sealed — skipped.
 *   <li>Components containing any ground-level (z=0) square are never reported: isolated ground
 *       pockets (fenced yards) are climbable/hoppable and out of scope.
 * </ul>
 */
public final class MapScanCollector {

    /** Answers whether a chunk exists in the map's lot data (vs. ocean/void). */
    public interface LotData {
        boolean chunkHasLotData(int chunkX, int chunkY);
    }

    private final HashMap<Long, Integer> squareComp = new HashMap<>();
    private final HashMap<Long, ArrayList<Long>> candidatesByChunk = new HashMap<>();
    private final HashSet<Long> scannedChunks = new HashSet<>();

    private final ArrayList<Integer> parent = new ArrayList<>();
    private final BitSet poisoned = new BitSet();
    private final BitSet hasGround = new BitSet();
    private final ArrayList<HashSet<Long>> contacts = new ArrayList<>();

    private int poisonedComponents = 0;

    public int candidateSquareCount() {
        return squareComp.size();
    }

    /** Folds one window's results into the global state. Call once per completed window. */
    public void addWindow(WindowScanResult result) {
        // Poison rule A: squares registered as candidates by earlier windows that this window
        // covers but does NOT consider candidates were proven reachable here.
        for (int cy = result.minChunkY; cy <= result.maxChunkY; cy++) {
            for (int cx = result.minChunkX; cx <= result.maxChunkX; cx++) {
                if (!result.isChunkLoaded(cx, cy)) {
                    continue;
                }
                ArrayList<Long> known = candidatesByChunk.get(CandidateComponent.packChunk(cx, cy));
                if (known == null) {
                    continue;
                }
                for (int i = 0; i < known.size(); i++) {
                    long sq = known.get(i);
                    if (!result.isCandidate(sq)) {
                        poison(find(squareComp.get(sq)));
                    }
                }
            }
        }

        for (CandidateComponent component : result.getComponents()) {
            int comp = newComponent(component);
            for (long sq : component.squares) {
                Integer existing = squareComp.get(sq);
                if (existing != null) {
                    comp = union(comp, existing);
                    continue;
                }
                long chunkKey = chunkKeyOf(sq);
                if (scannedChunks.contains(chunkKey)) {
                    // Poison rule B: an earlier window covered this square and did not flag it —
                    // it was reachable (or ground) there.
                    poison(find(comp));
                    continue;
                }
                squareComp.put(sq, comp);
                candidatesByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(sq);
            }
        }

        for (int cy = result.minChunkY; cy <= result.maxChunkY; cy++) {
            for (int cx = result.minChunkX; cx <= result.maxChunkX; cx++) {
                if (result.isChunkLoaded(cx, cy)) {
                    scannedChunks.add(CandidateComponent.packChunk(cx, cy));
                }
            }
        }
    }

    /** Final verdicts. Call once after the last window. */
    public FinalResult finish(LotData lotData) {
        FinalResult out = new FinalResult();
        // classify each distinct root once, then assign squares of sealed roots
        HashMap<Integer, SealedRegion> sealedByRoot = new HashMap<>();
        HashSet<Integer> groundRoots = new HashSet<>();
        HashSet<Integer> failOpenRoots = new HashSet<>();
        for (Integer comp : squareComp.values()) {
            int root = find(comp);
            if (sealedByRoot.containsKey(root)
                    || groundRoots.contains(root)
                    || failOpenRoots.contains(root)
                    || poisoned.get(root)) {
                continue;
            }
            if (hasGround.get(root)) {
                groundRoots.add(root);
            } else if (failsOpen(root, lotData)) {
                failOpenRoots.add(root);
            } else {
                sealedByRoot.put(root, new SealedRegion());
            }
        }
        for (Map.Entry<Long, Integer> entry : squareComp.entrySet()) {
            SealedRegion region = sealedByRoot.get(find(entry.getValue()));
            if (region != null) {
                region.add(entry.getKey());
            }
        }
        out.sealedRegions = new ArrayList<>(sealedByRoot.values());
        out.poisonedComponents = poisonedComponents;
        out.groundComponentsSkipped = groundRoots.size();
        out.failOpenComponents = failOpenRoots.size();
        return out;
    }

    private boolean failsOpen(int root, LotData lotData) {
        HashSet<Long> contactSet = contacts.get(root);
        if (contactSet == null) {
            return false;
        }
        for (long chunk : contactSet) {
            if (scannedChunks.contains(chunk)) {
                continue;
            }
            if (lotData.chunkHasLotData(
                    CandidateComponent.unpackChunkX(chunk),
                    CandidateComponent.unpackChunkY(chunk))) {
                return true;
            }
        }
        return false;
    }

    private static long chunkKeyOf(long packedSquare) {
        return CandidateComponent.packChunk(
                Math.floorDiv(SquareCoord.unpackX(packedSquare), ReachabilityScanEngine.CHUNK_DIM),
                Math.floorDiv(SquareCoord.unpackY(packedSquare), ReachabilityScanEngine.CHUNK_DIM));
    }

    private int newComponent(CandidateComponent component) {
        int id = parent.size();
        parent.add(id);
        if (component.hasGroundSquare) {
            hasGround.set(id);
        }
        HashSet<Long> contactSet = null;
        if (component.contactChunks.length > 0) {
            contactSet = new HashSet<>();
            for (long chunk : component.contactChunks) {
                contactSet.add(chunk);
            }
        }
        contacts.add(contactSet);
        return id;
    }

    private int find(int i) {
        int root = i;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        while (parent.get(i) != root) {
            int next = parent.get(i);
            parent.set(i, root);
            i = next;
        }
        return root;
    }

    /** Returns the surviving root. */
    private int union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra == rb) {
            return ra;
        }
        // merge smaller contact set into larger to bound merge cost
        HashSet<Long> ca = contacts.get(ra);
        HashSet<Long> cb = contacts.get(rb);
        int keep = ra;
        int drop = rb;
        if (sizeOf(cb) > sizeOf(ca)) {
            keep = rb;
            drop = ra;
        }
        parent.set(drop, keep);
        if (hasGround.get(drop)) {
            hasGround.set(keep);
        }
        if (poisoned.get(drop)) {
            poisoned.set(keep);
        }
        HashSet<Long> keepContacts = contacts.get(keep);
        HashSet<Long> dropContacts = contacts.get(drop);
        if (dropContacts != null) {
            if (keepContacts == null) {
                contacts.set(keep, dropContacts);
            } else {
                keepContacts.addAll(dropContacts);
            }
            contacts.set(drop, null);
        }
        return keep;
    }

    private static int sizeOf(HashSet<Long> set) {
        return set == null ? 0 : set.size();
    }

    private void poison(int root) {
        if (!poisoned.get(root)) {
            poisoned.set(root);
            poisonedComponents++;
        }
    }

    /** Aggregate outcome of a completed scan. */
    public static final class FinalResult {
        public ArrayList<SealedRegion> sealedRegions = new ArrayList<>();
        public int poisonedComponents;
        public int groundComponentsSkipped;
        public int failOpenComponents;

        public int sealedSquareCount() {
            int total = 0;
            for (int i = 0; i < sealedRegions.size(); i++) {
                total += sealedRegions.get(i).squareCount();
            }
            return total;
        }
    }
}
