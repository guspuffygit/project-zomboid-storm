package io.pzstorm.storm.advice.popmansaveadopt;

import io.pzstorm.storm.patch.fixes.PopManSaveAdoptFix;
import net.bytebuddy.asm.Advice;

/**
 * Inlined around {@code PopManCore.save()} and {@code PopManCore.saveCell(int, int)}. Entry
 * snapshots the staged live zombies and the per-cell counts, exit removes the twins the transpiled
 * body adopted and restores the counts — see {@link
 * io.pzstorm.storm.patch.fixes.PopManSaveAdoptFixPatch} for the rationale.
 *
 * <p>{@code @Advice.This} is typed {@code Object} so the inlined call site does not encode a
 * checkcast against the popman class being redefined. The snapshot rides {@code @Advice.Enter}
 * rather than a static, so a save on the popman worker and a cell save on the same thread can never
 * see each other's scratch.
 *
 * <p>No {@code onThrowable}: if the transpiled body throws, the file was not written and the exit
 * advice is skipped; the next save starts from a fresh snapshot.
 */
public class PopManSaveAdoptAdvice {

    @Advice.OnMethodEnter
    public static Object onEnter(@Advice.This Object core) {
        return PopManSaveAdoptFix.beforeSave(core);
    }

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object core, @Advice.Enter Object snapshot) {
        PopManSaveAdoptFix.afterSave(core, snapshot);
    }
}
