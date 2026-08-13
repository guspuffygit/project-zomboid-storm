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
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code FBORenderCell} bytecode: every per-object {@code
 * getOptionDoWindSpriteEffects()} / {@code isClimbing()} / {@code getShaderEnable()} call inside
 * the five {@code isObjectRenderLayer_*} helpers is rewritten to the matching {@link
 * FBORenderCellHoistCache} bridge, the two refresh entry points invoke {@code
 * FBORenderCellHoistCache.refresh()}, and neither the substitutions nor the refresh leak into any
 * other method. A silent {@code MemberSubstitution.relaxed()} signature mismatch would show up here
 * as a surviving vanilla call.
 *
 * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
 * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class FBORenderCellRenderLayerHoistPatchTest implements UnitTest {

    private static final String FBO_RENDER_CELL = "zombie/iso/fboRenderChunk/FBORenderCell";
    private static final String CACHE_INTERNAL =
            FBORenderCellHoistCache.class.getName().replace('.', '/');
    private static final String SQUARE_OVERLOAD_DESC = "(Lzombie/iso/IsoGridSquare;)V";

    @Test
    void patchRewritesHelperCallSitesAndInsertsRefresh() throws Exception {
        String resourcePath = FBO_RENDER_CELL + ".class";
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "FBORenderCell.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new FBORenderCellRenderLayerHoistPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Counts counts = countInvocations(transformed);

        assertHelper(counts, "isObjectRenderLayer_Floor", 0, 0, 2);
        assertHelper(counts, "isObjectRenderLayer_MinusFloor", 1, 1, 0);
        assertHelper(counts, "isObjectRenderLayer_MinusFloorSE", 0, 1, 0);
        assertHelper(counts, "isObjectRenderLayer_TranslucentFloor", 0, 0, 1);
        assertHelper(counts, "isObjectRenderLayer_Translucent", 1, 0, 0);

        assertTrue(
                counts.refreshCallsByMethod.getOrDefault("calculateObjectRenderInfo:square", 0)
                        >= 1,
                "calculateObjectRenderInfo(IsoGridSquare) should call HoistCache.refresh()");
        assertTrue(
                counts.refreshCallsByMethod.getOrDefault("renderJoinedRoofTile", 0) >= 1,
                "renderJoinedRoofTile should call HoistCache.refresh()");
        assertEquals(
                2,
                counts.refreshCallsByMethod.size(),
                "HoistCache.refresh() must only be woven into the two entry points; got "
                        + counts.refreshCallsByMethod.keySet());

        assertEquals(
                0,
                counts.bridgeCallsOutsideHelpers,
                "bridge substitutions must not leak outside the isObjectRenderLayer_* helpers;"
                        + " got "
                        + counts.bridgeCallsOutsideHelpers);
    }

    private static void assertHelper(
            Counts counts, String helper, int wind, int climbing, int shader) {
        assertEquals(
                0,
                counts.vanillaCallsByHelper.getOrDefault(helper, 0),
                helper + " should have no remaining vanilla hoisted-lookup calls");
        assertEquals(
                wind,
                counts.bridgeCalls(helper, "getOptionDoWindSpriteEffects"),
                helper + " wind-option bridge call count");
        assertEquals(
                climbing,
                counts.bridgeCalls(helper, "isClimbing"),
                helper + " isClimbing bridge call count");
        assertEquals(
                shader,
                counts.bridgeCalls(helper, "getShaderEnable"),
                helper + " getShaderEnable bridge call count");
    }

    private static boolean isHelper(String methodName) {
        return methodName.startsWith("isObjectRenderLayer_");
    }

    private static boolean isHoistedLookup(String owner, String name, String desc) {
        if (!"()Z".equals(desc)) {
            return false;
        }
        return "getOptionDoWindSpriteEffects".equals(name)
                || "isClimbing".equals(name)
                || ("getShaderEnable".equals(name) && "zombie/iso/IsoWater".equals(owner));
    }

    private static Counts countInvocations(byte[] classBytes) {
        Counts counts = new Counts();
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
                                final String methodName = name;
                                final String methodDesc = descriptor;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (CACHE_INTERNAL.equals(owner)) {
                                            if ("refresh".equals(mName)) {
                                                String key =
                                                        "calculateObjectRenderInfo"
                                                                                .equals(methodName)
                                                                        && SQUARE_OVERLOAD_DESC
                                                                                .equals(methodDesc)
                                                                ? "calculateObjectRenderInfo:square"
                                                                : methodName;
                                                counts.refreshCallsByMethod.merge(
                                                        key, 1, Integer::sum);
                                            } else if (isHelper(methodName)) {
                                                counts.bridgeCallsByHelper.merge(
                                                        methodName + "#" + mName, 1, Integer::sum);
                                            } else {
                                                counts.bridgeCallsOutsideHelpers++;
                                            }
                                        } else if (isHelper(methodName)
                                                && isHoistedLookup(owner, mName, mDesc)) {
                                            counts.vanillaCallsByHelper.merge(
                                                    methodName, 1, Integer::sum);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class Counts {
        final Map<String, Integer> vanillaCallsByHelper = new HashMap<>();
        final Map<String, Integer> bridgeCallsByHelper = new HashMap<>();
        final Map<String, Integer> refreshCallsByMethod = new HashMap<>();
        int bridgeCallsOutsideHelpers;

        int bridgeCalls(String helper, String bridge) {
            return bridgeCallsByHelper.getOrDefault(helper + "#" + bridge, 0);
        }
    }
}
