package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ZpopCellTest implements UnitTest {

    private static final Path REAL_SAVES =
            Path.of(System.getProperty("user.home"), "Zomboid/Saves");

    private static byte[] writeToBytes(ZpopCell cell, List<String> names) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cell.write(new DataOutputStream(out), names);
        return out.toByteArray();
    }

    private static ZpopCell readFromBytes(byte[] bytes, List<String> runtimeNames)
            throws IOException {
        return ZpopCell.read(new DataInputStream(new ByteArrayInputStream(bytes)), runtimeNames);
    }

    @Test
    void emptyCellIsAlwaysOneThousandTwentyFourChunkRecords() throws IOException {
        ZpopCell cell = new ZpopCell();
        List<String> names = List.of("police", "generic02");
        byte[] bytes = writeToBytes(cell, names);

        int header = 2 + 2 + (2 + 6) + (2 + 9) + 4 + 4;
        assertEquals(header + 1024 * (2 + 2 + 4 + 4), bytes.length);
        assertEquals(1024, cell.chunks.size());
    }

    @Test
    void roundTripsZombiesAndOpaqueFields() throws IOException {
        List<String> names = List.of("police", "generic02");
        ZpopCell cell = new ZpopCell();
        cell.lastRepopTime = 151.61061F;
        cell.lastRedistributeTime = 149.35324F;

        ZpopChunk chunk = cell.chunk(0, 3);
        chunk.basePop = 0;
        chunk.lastSeenTime = 0.0F;
        chunk.lastRepopTime = 3.3987236F;
        PopManZombie z = new PopManZombie();
        z.x = 15360.5F;
        z.y = 2844.4F;
        z.z = 1.0F;
        z.dir = 5;
        z.stateFlags = 5;
        z.descriptorID = 0x00A00109;
        chunk.zombies.add(z);

        byte[] bytes = writeToBytes(cell, names);
        ZpopCell back = readFromBytes(bytes, null);

        assertEquals(ZpopCell.WRITE_VERSION, back.version);
        assertEquals(151.61061F, back.lastRepopTime);
        assertEquals(149.35324F, back.lastRedistributeTime);
        assertEquals(names, back.outfitNames);

        ZpopChunk backChunk = back.chunk(0, 3);
        assertEquals(1, backChunk.zombies.size());
        assertEquals(3.3987236F, backChunk.lastRepopTime);
        PopManZombie bz = backChunk.zombies.get(0);
        assertEquals(15360.5F, bz.x);
        assertEquals(2844.4F, bz.y);
        assertEquals(1.0F, bz.z);
        assertEquals((byte) 5, bz.dir);
        assertEquals(5, bz.stateFlags);
        assertEquals(0x00A00109, bz.descriptorID);
    }

    @Test
    void unsupportedVersionsAreRefusedRatherThanMisparsed() throws IOException {
        byte[] bytes = writeToBytes(new ZpopCell(), List.of());
        bytes[0] = 0;
        bytes[1] = 3;

        IOException e = assertThrows(IOException.class, () -> readFromBytes(bytes, null));
        assertTrue(e.getMessage().contains("unsupported zpop version 3"));
    }

    @Test
    void outfitRemapSurvivesAnIndexShift() {
        List<String> fileNames = List.of("agent", "police", "generic02");
        Map<String, Integer> runtime = new HashMap<>();
        runtime.put("generic02", 7);

        int id = (2 << 16) | 0x0109 | 0x80000000;
        int remapped = ZpopCell.remapOutfitID(id, fileNames, runtime);

        assertEquals(7, (remapped & 0x7fffffff) >> 16, "index follows the name");
        assertEquals(0x0109, remapped & 0xffff, "variant and hat-fallen survive");
        assertEquals(0x80000000, remapped & 0x80000000, "female bit survives");
    }

    @Test
    void aVanishedOutfitDropsTheWholeDescriptor() {
        List<String> fileNames = List.of("agent", "removedbyamodupdate");
        Map<String, Integer> runtime = new HashMap<>();
        runtime.put("agent", 0);

        assertEquals(0, ZpopCell.remapOutfitID((1 << 16) | 0x42, fileNames, runtime));
        assertEquals(0, ZpopCell.remapOutfitID((99 << 16), fileNames, runtime), "index past table");
        assertEquals(0, ZpopCell.remapOutfitID(0, fileNames, runtime));
    }

    /**
     * The real proof: every zpop cell file on this machine must survive read-then-write unchanged,
     * byte for byte. Skipped where no save data exists.
     */
    @Test
    void realSaveFilesRoundTripByteIdentically() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(REAL_SAVES), "no local save data");

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(REAL_SAVES)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("zpop_"))
                    .filter(p -> !p.getFileName().toString().equals("zpop_virtual.bin"))
                    .forEach(files::add);
        }
        Assumptions.assumeFalse(files.isEmpty(), "no zpop cell files found");

        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            byte[] original = Files.readAllBytes(file);
            try {
                ZpopCell cell = readFromBytes(original, null);
                byte[] rewritten = writeToBytes(cell, cell.outfitNames);
                if (!java.util.Arrays.equals(original, rewritten)) {
                    failures.add(file + " (" + original.length + " -> " + rewritten.length + ")");
                }
            } catch (Exception e) {
                failures.add(file + " threw " + e);
            }
        }

        assertArrayEquals(
                new String[0],
                failures.toArray(new String[0]),
                failures.size() + " of " + files.size() + " zpop files failed to round-trip");
    }

    /**
     * A version-4 zombie is 14 bytes, not 18. Reading one with the modern layout would not fail, it
     * would slide every following record.
     */
    @Test
    void versionFourZombiesUseTheNarrowLegacyStateByte() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(4);
        out.writeShort(1);
        out.writeShort(2);
        out.write("pd".getBytes(StandardCharsets.UTF_8));
        out.writeFloat(1.0F);
        out.writeFloat(2.0F);
        for (int i = 0; i < ZpopChunk.CHUNKS_PER_CELL; i++) {
            out.writeShort(0);
            out.writeShort(i == 0 ? 1 : 0);
            if (i == 0) {
                out.writeFloat(1200.5F);
                out.writeFloat(1300.5F);
                out.writeByte(3);
                out.writeByte(5);
                out.writeByte(1);
                out.writeInt(0x00000109);
            }
            out.writeFloat(0.0F);
            out.writeFloat(0.0F);
        }

        ZpopCell cell =
                ZpopCell.read(
                        new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), null);
        PopManZombie zombie = cell.chunks.get(0).zombies.get(0);

        assertEquals(4, cell.version);
        assertEquals(1200.5F, zombie.x);
        assertEquals(5, zombie.dir);
        assertEquals(6, zombie.stateFlags, "legacy state 1 expands to the modern bit pair");
        assertEquals(0x00000109, zombie.descriptorID);
    }

    @Test
    void everyLegacyStateValueHasAModernEquivalent() {
        assertEquals(6, ZpopCell.expandLegacyState(1));
        assertEquals(8, ZpopCell.expandLegacyState(2));
        assertEquals(2, ZpopCell.expandLegacyState(3));
        assertEquals(4, ZpopCell.expandLegacyState(4));
        assertEquals(0, ZpopCell.expandLegacyState(0));
        assertEquals(0, ZpopCell.expandLegacyState(99));
    }
}
