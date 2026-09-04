package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Attaches enter/exit advice to the pure-Java popman's {@code PopManCore.save()} and {@code
 * PopManCore.saveCell(int, int)} so the live zombies the game stages for a save are written to the
 * cell file but not kept as virtual twins in memory. The logic lives in {@link PopManSaveAdoptFix};
 * the transpiled popman code itself is left as it is, an exact replica of the DLL.
 *
 * <h2>The bug this patches — "zombie mitosis"</h2>
 *
 * <p>Every world save ({@code ServerMap} → {@code MapCollisionData.save()} → {@code
 * ZombiePopulationManager.beginSaveRealZombies()} → {@code n_save}) stages <em>every live
 * zombie</em> so the cell files record them. The population manager re-homes each staged zombie
 * into the cell that owns its tile and folds it into that cell's population: it lands in the
 * chunk's virtual list with {@code virtualCount++} and {@code realCount--}, while the real zombie
 * it was copied from keeps walking around. The copy materialises later — on a chunk reload, a sound
 * recruitment, a redistribute, a horde ending — on top of the original, and the next save stages
 * both. Observed on the local server with {@code SaveWorldEveryMinutes=1}: the player's cell gained
 * exactly its real count in virtual zombies on every save (104 → 130 → 156 with 26 reals) and 117
 * virtual zombies stood at the exact float positions of 26 live ones within minutes.
 *
 * <p>This is the full-save path; the per-chunk-unload path ({@code requestSaveCell} → {@code
 * n_saveCell}) is the one {@link RequestSaveCellSuppressPatch} short-circuits, and both are covered
 * here because the popman worker can reach {@code saveCell} through its own queue.
 *
 * <h2>Why enter/exit advice and not a body replacement</h2>
 *
 * <p>The adoption is a mid-method side effect of the same call that writes the file, and the file
 * must still contain the staged zombies or a restart loses every zombie that was alive. So the
 * transpiled body runs unchanged — the file it writes is exactly the one the DLL writes, virtual
 * plus staged — and the exit advice takes the adopted objects back out of the resident lists and
 * puts the two counts back to their entry values. Nothing else in either method touches the
 * population. A throw anywhere in the fix latches it off permanently and the transpiled behaviour
 * resumes.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only and on {@code -Dstorm.popman.java=true} in {@code StormClassTransformers};
 * without the pure-Java popman the target class is never loaded.
 */
public class PopManSaveAdoptFixPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.popmansaveadopt.";

    public PopManSaveAdoptFixPatch() {
        super("io.pzstorm.storm.popman.PopManCore");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "PopManSaveAdoptAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("save")
                                        .and(ElementMatchers.takesArguments(0))
                                        .or(
                                                ElementMatchers.named("saveCell")
                                                        .and(
                                                                ElementMatchers.takesArguments(
                                                                        int.class, int.class)))));
    }
}
