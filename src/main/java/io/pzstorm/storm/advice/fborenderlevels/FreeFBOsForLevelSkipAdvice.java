package io.pzstorm.storm.advice.fborenderlevels;

import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.fboRenderChunk.FBORenderLevels.freeFBOsForLevel(int)}.
 *
 * <p>{@code FBORenderCell} calls this every frame for every level of every loaded off-screen chunk
 * (and for every off-screen level of on-screen chunks) with no already-free guard — with a full
 * chunk grid that is well over a thousand calls per frame, the vast majority no-ops that still pay
 * {@code getNLevels} index arithmetic and two slot checks. Live sampling while driving showed
 * {@code freeFBOsForLevel} as a ~1% MainThread leaf.
 *
 * <p>The patch defines {@code storm$noFBOs} on the class: {@code true} means "this chunk currently
 * holds no render chunks in any level/zoom slot, so freeing is a guaranteed no-op" and the body is
 * skipped on a single field read. The flag is maintained conservatively (default {@code false} =
 * never skip):
 *
 * <ul>
 *   <li>after a non-skipped {@code freeFBOsForLevel} run, this advice's exit recomputes it from
 *       ground truth via the package-private {@code hasNoRenderChunks()} (reflective call, cached
 *       {@code Method}) — so the first call on a chunk with nothing to free flips the flag and
 *       every subsequent call is one field read;
 *   <li>{@code createFBOForLevel} clears it ({@link CreateFBOFlagClearAdvice});
 *   <li>{@code clearCache()} sets it ({@link ClearCacheFlagSetAdvice}).
 * </ul>
 *
 * <p>{@code freeChunk()} bypasses {@code freeFBOsForLevel}, leaving the flag {@code false} after
 * freeing everything — self-healing: the next {@code freeFBOsForLevel} call runs the vanilla no-op
 * once and its exit recompute flips the flag. On reflective failure the flag is forced {@code
 * false}, i.e. permanent vanilla behavior. Chunks that keep FBOs on some level pay one reflective
 * recompute per off-screen-level call; while driving those (multi-story on-screen chunks) are a
 * small minority of call volume.
 *
 * <p>Threading: all FBO bookkeeping runs on MainThread, same as vanilla.
 */
public class FreeFBOsForLevelSkipAdvice {

    public static final Method HAS_NO_RENDER_CHUNKS = resolveHasNoRenderChunks();

    public static Method resolveHasNoRenderChunks() {
        try {
            Method method =
                    Class.forName("zombie.iso.fboRenderChunk.FBORenderLevels")
                            .getDeclaredMethod("hasNoRenderChunks");
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.FieldValue("storm$noFBOs") boolean noFBOs) {
        return noFBOs;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter boolean skipped,
            @Advice.This Object self,
            @Advice.FieldValue(value = "storm$noFBOs", readOnly = false) boolean noFBOs) {
        if (!skipped) {
            boolean recomputed = false;
            try {
                if (HAS_NO_RENDER_CHUNKS != null) {
                    recomputed = (Boolean) HAS_NO_RENDER_CHUNKS.invoke(self);
                }
            } catch (Throwable t) {
                recomputed = false;
            }
            noFBOs = recomputed;
        }
    }
}
