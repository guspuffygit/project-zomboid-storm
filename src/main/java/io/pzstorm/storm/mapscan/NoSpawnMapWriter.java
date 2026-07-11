package io.pzstorm.storm.mapscan;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persists a completed map scan as (1) a compact binary no-spawn artifact for runtime spawn gates
 * to consume, and (2) a human-readable report listing every sealed region for auditing/map fixes.
 *
 * <p>Artifact format v1 (big-endian, via {@link DataOutputStream}): magic {@code 0x53544E53}
 * ("STNS"), int version=1, int entryCount, then per entry {@code int chunkX, int chunkY, int z,
 * long mask} where mask bit {@code localY * 8 + localX} marks a no-spawn square in that chunk
 * layer. Entries are sorted by (chunkX, chunkY, z) for determinism.
 */
public final class NoSpawnMapWriter {

    public static final int MAGIC = 0x53544E53;
    public static final int VERSION = 1;

    private NoSpawnMapWriter() {}

    public static void writeArtifact(File file, ArrayList<SealedRegion> regions)
            throws IOException {
        TreeMap<Long, HashMap<Integer, Long>> masksByChunk = new TreeMap<>();
        int entryCount = 0;
        for (int r = 0; r < regions.size(); r++) {
            ArrayList<Long> squares = regions.get(r).squares;
            for (int i = 0; i < squares.size(); i++) {
                long packed = squares.get(i);
                int x = SquareCoord.unpackX(packed);
                int y = SquareCoord.unpackY(packed);
                int z = SquareCoord.unpackZ(packed);
                int chunkX = Math.floorDiv(x, ReachabilityScanEngine.CHUNK_DIM);
                int chunkY = Math.floorDiv(y, ReachabilityScanEngine.CHUNK_DIM);
                long chunkKey = CandidateComponent.packChunk(chunkX, chunkY);
                HashMap<Integer, Long> layers =
                        masksByChunk.computeIfAbsent(chunkKey, k -> new HashMap<>());
                int localBit =
                        Math.floorMod(y, ReachabilityScanEngine.CHUNK_DIM)
                                        * ReachabilityScanEngine.CHUNK_DIM
                                + Math.floorMod(x, ReachabilityScanEngine.CHUNK_DIM);
                Long previous = layers.put(z, layers.getOrDefault(z, 0L) | (1L << localBit));
                if (previous == null) {
                    entryCount++;
                }
            }
        }

        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(entryCount);
            for (Map.Entry<Long, HashMap<Integer, Long>> chunkEntry : masksByChunk.entrySet()) {
                int chunkX = CandidateComponent.unpackChunkX(chunkEntry.getKey());
                int chunkY = CandidateComponent.unpackChunkY(chunkEntry.getKey());
                ArrayList<Integer> zs = new ArrayList<>(chunkEntry.getValue().keySet());
                Collections.sort(zs);
                for (int i = 0; i < zs.size(); i++) {
                    int z = zs.get(i);
                    out.writeInt(chunkX);
                    out.writeInt(chunkY);
                    out.writeInt(z);
                    out.writeLong(chunkEntry.getValue().get(z));
                }
            }
        }
    }

    public static void writeReport(
            File file,
            MapScanCollector.FinalResult result,
            String scanBoundsDescription,
            long scanDurationMillis,
            int windowsScanned,
            int windowsFailed)
            throws IOException {
        ArrayList<SealedRegion> regions = new ArrayList<>(result.sealedRegions);
        regions.sort(Comparator.comparingInt(SealedRegion::squareCount).reversed());

        try (BufferedWriter out =
                new BufferedWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.write("Storm map reachability scan report");
            out.newLine();
            out.write("scan bounds: " + scanBoundsDescription);
            out.newLine();
            out.write("windows scanned: " + windowsScanned + " (failed: " + windowsFailed + ")");
            out.newLine();
            out.write("duration: " + (scanDurationMillis / 1000L) + "s");
            out.newLine();
            out.write(
                    "sealed regions: "
                            + regions.size()
                            + " ("
                            + result.sealedSquareCount()
                            + " squares) | reachable-after-stitch components: "
                            + result.poisonedComponents
                            + " | ground-level components skipped: "
                            + result.groundComponentsSkipped
                            + " | fail-open (unscanned contact): "
                            + result.failOpenComponents);
            out.newLine();
            out.newLine();
            out.write(
                    "Each line: squares, z-range, bounding box (world tile coords), sample tile.");
            out.newLine();
            for (int i = 0; i < regions.size(); i++) {
                SealedRegion region = regions.get(i);
                long sample = region.squares.get(0);
                out.write(
                        String.format(
                                "#%-4d %6d sq  z=%d..%d  bbox=(%d,%d)-(%d,%d)  sample=(%d,%d,%d)",
                                i + 1,
                                region.squareCount(),
                                region.minZ,
                                region.maxZ,
                                region.minX,
                                region.minY,
                                region.maxX,
                                region.maxY,
                                SquareCoord.unpackX(sample),
                                SquareCoord.unpackY(sample),
                                SquareCoord.unpackZ(sample)));
                out.newLine();
            }
        }
    }
}
