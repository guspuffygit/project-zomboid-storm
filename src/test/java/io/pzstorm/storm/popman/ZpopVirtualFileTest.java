package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.pzstorm.storm.UnitTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ZpopVirtualFileTest implements UnitTest {

    private static final Path REAL_SAVES =
            Path.of(System.getProperty("user.home"), "Zomboid/Saves");

    @Test
    void roundTripsHordesAndTheirMembers() throws IOException {
        ZpopVirtualFile file = new ZpopVirtualFile();
        ZpopVirtualFile.Group group = new ZpopVirtualFile.Group();
        group.field0x04 = 7;
        group.field0x08 = -1;
        PopManZombie leader = new PopManZombie();
        leader.x = 1200.5F;
        leader.y = 1300.25F;
        leader.z = 1.0F;
        leader.dir = 5;
        leader.stateFlags = 0x05;
        leader.descriptorID = 0x00a00109;
        group.members.add(leader);
        file.groups.add(group);

        List<String> outfits = List.of("generic01", "generic02");
        ZpopVirtualFile reread = readFromBytes(writeToBytes(file, outfits), null);

        assertEquals(1, reread.groups.size());
        assertEquals(outfits, reread.outfitNames);
        ZpopVirtualFile.Group back = reread.groups.get(0);
        assertEquals(7, back.field0x04);
        assertEquals(-1, back.field0x08);
        assertEquals(1, back.members.size());
        assertEquals(1200.5F, back.members.get(0).x);
        assertEquals(0x00a00109, back.members.get(0).descriptorID);
    }

    /**
     * The writer clamps the member count to at least one, and the reader reads at least one back.
     */
    @Test
    void anEmptyGroupStillCarriesOneMemberSlot() throws IOException {
        ZpopVirtualFile file = new ZpopVirtualFile();
        file.groups.add(new ZpopVirtualFile.Group());

        byte[] bytes = writeToBytes(file, List.of("generic01"));

        assertThrows(
                IOException.class,
                () -> readFromBytes(bytes, null),
                "the count is clamped up to one but no member was written, so the read overruns");
    }

    @Test
    void unsupportedVersionsAreRefusedRatherThanMisparsed() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        assertThrows(
                IOException.class,
                () -> {
                    out.writeShort(5);
                    out.writeShort(0);
                    out.writeInt(0);
                    readFromBytes(bytes.toByteArray(), null);
                });
    }

    @Test
    void realVirtualFilesRoundTripByteIdentically() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(REAL_SAVES), "no local save data");

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(REAL_SAVES)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("zpop_virtual.bin"))
                    .forEach(files::add);
        }
        Assumptions.assumeFalse(files.isEmpty(), "no zpop_virtual.bin found");

        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            byte[] original = Files.readAllBytes(file);
            try {
                ZpopVirtualFile parsed = readFromBytes(original, null);
                byte[] rewritten = writeToBytes(parsed, parsed.outfitNames);
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
                failures.size() + " of " + files.size() + " virtual files failed to round-trip");
    }

    private static ZpopVirtualFile readFromBytes(byte[] bytes, List<String> runtimeOutfitNames)
            throws IOException {
        return ZpopVirtualFile.read(
                new DataInputStream(new ByteArrayInputStream(bytes)), runtimeOutfitNames);
    }

    private static byte[] writeToBytes(ZpopVirtualFile file, List<String> runtimeOutfitNames)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        file.write(new DataOutputStream(bytes), runtimeOutfitNames);
        return bytes.toByteArray();
    }
}
