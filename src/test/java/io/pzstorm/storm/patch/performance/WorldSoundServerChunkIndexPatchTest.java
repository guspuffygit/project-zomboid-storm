package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.Test;

/**
 * Weave-verifies {@code WorldSoundServerChunkIndexPatch} against the real {@code WorldSoundManager}
 * class bytes: the coalescer enter/exit advice lands only in the 13-arg {@code addSound} body
 * overload, the stress replacement lands only in {@code getStressFromSounds} (with the vanilla
 * additive loop retained as the fail-soft fallback), the expiry/teardown hooks land in {@code
 * update}/{@code KillCell}, and {@code MemberSubstitution} replaces every {@code GameServer.server}
 * read in the three sound-query methods with {@code StormServerChunkSoundIndex.readServerFlag}.
 */
class WorldSoundServerChunkIndexPatchTest implements UnitTest {

    private static final String WORLD_SOUND_MANAGER = "zombie/WorldSoundManager";
    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String INDEX = "io/pzstorm/storm/sound/StormServerChunkSoundIndex";
    private static final String COALESCER = "io/pzstorm/storm/sound/StormRepeatingSoundCoalescer";

    @Test
    void patchWiresCoalescerIndexAndStressReplacement() throws Exception {
        byte[] rawClass;
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream(WORLD_SOUND_MANAGER + ".class")) {
            assertNotNull(is, "WorldSoundManager.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new WorldSoundServerChunkIndexPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Map<String, Counts> methods = countPerMethod(transformed);

        String bodyOverload = null;
        for (String key : methods.keySet()) {
            if (key.startsWith("addSound(")
                    && Type.getArgumentTypes(key.substring(key.indexOf('('))).length == 13) {
                assertEquals(null, bodyOverload, "exactly one 13-arg addSound body overload");
                bodyOverload = key;
            }
        }
        assertNotNull(bodyOverload, "WorldSoundManager must declare the 13-arg addSound overload");

        int totalTryCoalesce = 0;
        int totalStress = 0;
        int totalOnSoundAdded = 0;
        for (Map.Entry<String, Counts> entry : methods.entrySet()) {
            totalTryCoalesce += entry.getValue().tryCoalesceCalls;
            totalStress += entry.getValue().stressFromSoundsCalls;
            totalOnSoundAdded += entry.getValue().onSoundAddedCalls;
        }

        Counts addSound = methods.get(bodyOverload);
        assertEquals(
                1,
                addSound.tryCoalesceCalls,
                "the 13-arg addSound body should contain exactly one inlined tryCoalesce call");
        assertEquals(
                1,
                addSound.onSoundAddedCalls,
                "the 13-arg addSound body should contain exactly one inlined onSoundAdded call");
        assertEquals(1, totalTryCoalesce, "tryCoalesce must not leak outside the body overload");
        assertEquals(1, totalOnSoundAdded, "onSoundAdded must not leak outside the body overload");

        Counts stress = methods.get("getStressFromSounds(III)F");
        assertNotNull(stress, "WorldSoundManager must declare getStressFromSounds(int,int,int)");
        assertEquals(
                1,
                stress.stressFromSoundsCalls,
                "getStressFromSounds should contain exactly one inlined replacement call");
        assertEquals(1, totalStress, "stressFromSounds must not leak outside getStressFromSounds");
        assertTrue(
                stress.distanceManhattenCalls >= 1,
                "getStressFromSounds should retain the vanilla additive loop as fallback");

        assertTrue(
                sumForName(methods, "update").onUpdateStartCalls >= 1,
                "update should contain the inlined onUpdateStart expiry hook");
        assertTrue(
                sumForName(methods, "KillCell").onKillCellCalls >= 1,
                "KillCell should contain the inlined onKillCell teardown hook");

        for (String query :
                new String[] {"getSoundZomb", "getSoundAnimal", "getBiggestSoundZomb"}) {
            Counts counts = sumForName(methods, query);
            assertEquals(
                    0,
                    counts.serverReads,
                    query + " must have every GameServer.server read substituted");
            assertTrue(
                    counts.readServerFlagCalls >= 1,
                    query + " should read the server flag via readServerFlag()");
        }
    }

    private static Counts sumForName(Map<String, Counts> methods, String name) {
        Counts sum = new Counts();
        for (Map.Entry<String, Counts> entry : methods.entrySet()) {
            if (entry.getKey().startsWith(name + "(")) {
                sum.add(entry.getValue());
            }
        }
        return sum;
    }

    private static Map<String, Counts> countPerMethod(byte[] classBytes) {
        Map<String, Counts> counts = new HashMap<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                Counts method =
                                        counts.computeIfAbsent(
                                                name + descriptor, k -> new Counts());
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fname, String fdesc) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && GAME_SERVER.equals(owner)
                                                && "server".equals(fname)) {
                                            method.serverReads++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if (COALESCER.equals(owner)) {
                                            if ("tryCoalesce".equals(mname)) {
                                                method.tryCoalesceCalls++;
                                            } else if ("stressFromSounds".equals(mname)) {
                                                method.stressFromSoundsCalls++;
                                            }
                                        } else if (INDEX.equals(owner)) {
                                            if ("onSoundAdded".equals(mname)) {
                                                method.onSoundAddedCalls++;
                                            } else if ("onUpdateStart".equals(mname)) {
                                                method.onUpdateStartCalls++;
                                            } else if ("onKillCell".equals(mname)) {
                                                method.onKillCellCalls++;
                                            } else if ("readServerFlag".equals(mname)) {
                                                method.readServerFlagCalls++;
                                            }
                                        } else if ("DistanceManhatten".equals(mname)) {
                                            method.distanceManhattenCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return counts;
    }

    private static final class Counts {
        int serverReads;
        int tryCoalesceCalls;
        int stressFromSoundsCalls;
        int onSoundAddedCalls;
        int onUpdateStartCalls;
        int onKillCellCalls;
        int readServerFlagCalls;
        int distanceManhattenCalls;

        void add(Counts other) {
            serverReads += other.serverReads;
            tryCoalesceCalls += other.tryCoalesceCalls;
            stressFromSoundsCalls += other.stressFromSoundsCalls;
            onSoundAddedCalls += other.onSoundAddedCalls;
            onUpdateStartCalls += other.onUpdateStartCalls;
            onKillCellCalls += other.onKillCellCalls;
            readServerFlagCalls += other.readServerFlagCalls;
            distanceManhattenCalls += other.distanceManhattenCalls;
        }
    }
}
