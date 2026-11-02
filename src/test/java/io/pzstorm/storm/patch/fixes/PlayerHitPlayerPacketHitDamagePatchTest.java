package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import zombie.CombatManager;
import zombie.network.fields.hit.Hit;

/**
 * Verifies that {@link PlayerHitPlayerPacketHitDamagePatch} lands on {@code process} only, and
 * unit-tests the flag clearing in {@link PvpHitsAlwaysDamage}.
 */
class PlayerHitPlayerPacketHitDamagePatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/network/packets/hit/PlayerHitPlayerPacket";
    private static final String HELPER_CLASS = "io/pzstorm/storm/patch/fixes/PvpHitsAlwaysDamage";

    @Test
    void patchLandsOnProcessOnly() throws Exception {
        byte[] raw = readClassBytes(TARGET_CLASS + ".class");
        byte[] out = new PlayerHitPlayerPacketHitDamagePatch().transform(raw);
        assertNotNull(out);

        assertEquals(0, countHelperCalls(raw, "process", "()V"));
        assertTrue(countHelperCalls(out, "process", "()V") >= 1);
        assertEquals(0, countHelperCalls(out, "parse", null));
        assertEquals(0, countHelperCalls(out, "write", null));
    }

    @Test
    void clearsOnlyTheFlaggedHits() throws Exception {
        List<Hit> hits = new ArrayList<>();
        hits.add(hit(true));
        hits.add(hit(false));
        hits.add(hit(true));
        hits.add(new Hit());

        assertEquals(2, PvpHitsAlwaysDamage.clearIgnoreDamage(hits));
        for (Hit hit : hits) {
            assertFalse((boolean) field("isIgnoreDamage").get(hit));
            CombatManager.HitData data = (CombatManager.HitData) field("hitData").get(hit);
            if (data != null) {
                assertFalse(data.isIgnoreDamage());
            }
        }
        assertEquals(0, PvpHitsAlwaysDamage.clearIgnoreDamage(hits));
    }

    @Test
    void toleratesNullAndForeignEntries() {
        assertEquals(0, PvpHitsAlwaysDamage.clearIgnoreDamage(null));
        assertEquals(0, PvpHitsAlwaysDamage.clearIgnoreDamage(List.of("not a hit")));
        assertFalse(PvpHitsAlwaysDamage.isShoveHit((Object) null));
        assertFalse(PvpHitsAlwaysDamage.isShoveHit("not a packet"));
    }

    @Test
    void shovesKeepVanillaFlag() {
        assertTrue(PvpHitsAlwaysDamage.isShoveHit(true, false, false));
        assertTrue(PvpHitsAlwaysDamage.isShoveHit(true, false, true));
        assertTrue(PvpHitsAlwaysDamage.isShoveHit(true, true, false));
        assertFalse(PvpHitsAlwaysDamage.isShoveHit(true, true, true));
        assertFalse(PvpHitsAlwaysDamage.isShoveHit(false, false, false));
        assertFalse(PvpHitsAlwaysDamage.isShoveHit(false, true, true));
    }

    private static Hit hit(boolean ignoreDamage) throws Exception {
        CombatManager.HitData data =
                new CombatManager.HitData((byte) 1, false, ignoreDamage, false, true);
        Hit hit = new Hit();
        hit.set(10f, 1f, 1f, data, false, 0f);
        field("hitData").set(hit, data);
        return hit;
    }

    private static Field field(String name) throws Exception {
        Field field = Hit.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCalls(byte[] classBytes, String method, String desc) {
        int[] hits = new int[1];
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
                                if (!method.equals(name)
                                        || (desc != null && !desc.equals(descriptor))) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (HELPER_CLASS.equals(owner)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return hits[0];
    }
}
