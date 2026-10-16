package io.pzstorm.storm.shader;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;

class StormModelShaderCacheTest implements UnitTest {
    @BeforeEach
    @AfterEach
    void clear() {
        StormModelShaderCache.clear();
    }

    @Test
    void coldMissAndAllExactNativeVariants() throws Exception {
        assertNull(StormModelShaderCache.find("basicEffect", false, false));
        assertNull(StormModelShaderCache.find(null, false, false));
        for (boolean isStatic : new boolean[] {false, true}) {
            for (boolean instanced : new boolean[] {false, true}) {
                Shader shader = NativeShaderTestObjects.shader("basicEffect", isStatic, instanced);
                StormModelShaderCache.record(
                        ShaderManager.instance, "basicEffect", isStatic, instanced, shader);
                assertSame(
                        shader,
                        StormModelShaderCache.find(new String("basicEffect"), isStatic, instanced));
            }
        }
        assertEquals(4, StormModelShaderCache.cachedVariantCount());
        assertNull(StormModelShaderCache.find("BasicEffect", false, false));
        assertNull(StormModelShaderCache.find("missing", false, false));
        // Names that could collide with concatenated flag suffixes remain independent tuples.
        Shader suffix = NativeShaderTestObjects.shader("basicEffect_false_false", false, false);
        StormModelShaderCache.record(
                ShaderManager.instance, suffix.getName(), false, false, suffix);
        assertSame(suffix, StormModelShaderCache.find(suffix.getName(), false, false));
        assertEquals(5, StormModelShaderCache.cachedVariantCount());
    }

    @Test
    void rejectedFailureOrActualIdentityMismatchNeverWarms() throws Exception {
        Shader shader = NativeShaderTestObjects.shader("basicEffect", false, false);
        StormModelShaderCache.record(ShaderManager.instance, "basicEffect", false, false, null);
        StormModelShaderCache.record(ShaderManager.instance, "BasicEffect", false, false, shader);
        StormModelShaderCache.record(ShaderManager.instance, "basicEffect", true, false, shader);
        StormModelShaderCache.record(ShaderManager.instance, "basicEffect", false, true, shader);
        StormModelShaderCache.record(new ShaderManager(), "basicEffect", false, false, shader);
        StormModelShaderCache.record(null, "basicEffect", false, false, shader);
        StormModelShaderCache.record(ShaderManager.instance, null, false, false, shader);
        assertEquals(0, StormModelShaderCache.cachedVariantCount());
        assertNull(StormModelShaderCache.find("basicEffect", false, false));
        // Native Shader can report instanced=false even after a requested instanced=true compile.
        StormModelShaderCache.record(ShaderManager.instance, "basicEffect", false, true, shader);
        assertNull(StormModelShaderCache.find("basicEffect", false, true));
    }

    @Test
    void keepsFirstNativeIdentityAndClearAllowsReplacement() throws Exception {
        Shader first = NativeShaderTestObjects.shader("basicEffect", false, false);
        Shader second = NativeShaderTestObjects.shader("basicEffect", false, false);
        StormModelShaderCache.record(ShaderManager.instance, "basicEffect", false, false, first);
        StormModelShaderCache.record(ShaderManager.instance, "basicEffect", false, false, second);
        assertSame(first, StormModelShaderCache.find("basicEffect", false, false));
        StormModelShaderCache.clear();
        assertNull(StormModelShaderCache.find("basicEffect", false, false));
        StormModelShaderCache.record(ShaderManager.instance, "basicEffect", false, false, second);
        assertSame(second, StormModelShaderCache.find("basicEffect", false, false));
    }

    @Test
    void boundedIndexLeavesOverflowOnNativePath() throws Exception {
        Shader first = null;
        for (int i = 0; i <= StormModelShaderCache.MAX_ENTRIES; i++) {
            String name = "fixture" + i;
            Shader shader = NativeShaderTestObjects.shader(name, false, false);
            if (i == 0) first = shader;
            StormModelShaderCache.record(ShaderManager.instance, name, false, false, shader);
        }
        assertEquals(StormModelShaderCache.MAX_ENTRIES, StormModelShaderCache.cachedVariantCount());
        assertSame(first, StormModelShaderCache.find("fixture0", false, false));
        assertNull(
                StormModelShaderCache.find(
                        "fixture" + StormModelShaderCache.MAX_ENTRIES, false, false));
        StormModelShaderCache.record(ShaderManager.instance, "fixture0", false, false, first);
        assertSame(first, StormModelShaderCache.find("fixture0", false, false));
    }

    @Test
    void concurrentPublicationAndReadersSeeCompleteNativeIdentity() throws Exception {
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                final int workerIndex = worker;
                tasks.add(
                        () -> {
                            start.await();
                            for (int i = 0; i < 160; i++) {
                                String name = "worker" + workerIndex + "/shader" + i;
                                Shader shader =
                                        NativeShaderTestObjects.shader(name, (i & 1) == 0, false);
                                StormModelShaderCache.record(
                                        ShaderManager.instance,
                                        name,
                                        shader.isStatic(),
                                        false,
                                        shader);
                                Shader hit =
                                        StormModelShaderCache.find(name, shader.isStatic(), false);
                                // Over-capacity entries may deliberately miss; a published hit must
                                // be exact.
                                if (hit != null) {
                                    assertSame(shader, hit);
                                    assertEquals(name, hit.getName());
                                    assertEquals((i & 1) == 0, hit.isStatic());
                                    assertFalse(hit.isInstanced());
                                }
                            }
                            return null;
                        });
            }
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (var future : futures) future.get();
        }
        assertEquals(StormModelShaderCache.MAX_ENTRIES, StormModelShaderCache.cachedVariantCount());
    }
}
