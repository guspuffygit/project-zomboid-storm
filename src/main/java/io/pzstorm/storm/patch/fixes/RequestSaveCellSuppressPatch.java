package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Short-circuits {@code ZombiePopulationManager.requestSaveCell(int, int)} on the dedicated server.
 *
 * <p>Vanilla calls this method once per chunk unload from {@code IsoChunk.removeFromWorld:3151};
 * each call snapshots all live zombies in the surrounding 256x256 pop cell and the native {@code
 * n_saveCell} APPENDS them to {@code Saves/.../zpop/zpop_X_Y.bin} instead of overwriting. Result: a
 * single live zombie's 12-byte record is rewritten on every chunk unload as the player roams the
 * pop cell, and on chunk reload native emits every accumulated copy &mdash; the player sees N
 * identical zombies with sequential online IDs spawn at once ("zombie mitosis").
 *
 * <p>The global autosave path ({@code MapCollisionData.save} -&gt; {@code beginSaveRealZombies}
 * -&gt; native {@code n_save} writing {@code zpop_virtual.bin}) is independent and untouched, so
 * live zombie state is still persisted on the normal {@code SaveWorldEveryMinutes} cadence.
 *
 * <p>Server-only by registration in {@code StormClassTransformers}.
 */
public class RequestSaveCellSuppressPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.requestsavecellsuppress.";

    public RequestSaveCellSuppressPatch() {
        super("zombie.popman.ZombiePopulationManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "RequestSaveCellSuppressAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("requestSaveCell")
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        int.class, int.class))));
    }
}
