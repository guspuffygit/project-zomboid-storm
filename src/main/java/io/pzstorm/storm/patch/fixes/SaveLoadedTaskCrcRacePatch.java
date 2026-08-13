package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the shared-{@code CRC32} race in {@code ServerChunkLoader$SaveLoadedTask.save()}.
 *
 * <p>{@code save()} reads the outer {@code ServerChunkLoader.crcSave} four times
 * (reset/update/getValue/getValue) to decide whether the serialized chunk differs from the {@code
 * ChunkChecksum} dedup cache before writing. It executes concurrently on two threads: the SaveChunk
 * thread's run loop, and the LoadChunk thread via {@code SaveChunkThread.saveNow}, which drains and
 * runs matching queued tasks inline before every chunk load. Interleaving corrupts the computed
 * value — garbage lands in the dedup cache, causing redundant disk writes and redundant chunk
 * resends to clients through {@code PlayerDownloadServer}; a wrongly <i>skipped</i> write would
 * need a 2^-32 collision.
 *
 * <p>The substitution redirects every {@code crcSave} read inside {@code save()} to {@link
 * io.pzstorm.storm.map.StormChunkSaveCrc#crc(Object)}. All four reads on one thread resolve to the
 * same per-thread instance, so the reset-first sequence stays coherent; the outer field's
 * initialization is untouched. See {@code SaveChunkThreadCrcRacePatch} for the sibling race on
 * {@code SaveChunkThread.crc32}.
 *
 * <p>Fail-loud: the hook is name-string based, so a vanilla rename would otherwise silently no-op
 * and reintroduce the race. {@link #dynamicType} throws if {@code save} or the outer {@code
 * crcSave} are no longer declared — re-verify against the game source on update.
 *
 * <p>Registration-gated to the dedicated server ({@code StormEnv.isStormServer()}).
 */
public class SaveLoadedTaskCrcRacePatch extends StormClassTransformer {

    private static final String OUTER = "zombie.network.ServerChunkLoader";

    private static final String SCRATCH = "io.pzstorm.storm.map.StormChunkSaveCrc";

    public SaveLoadedTaskCrcRacePatch() {
        super("zombie.network.ServerChunkLoader$SaveLoadedTask");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(className).resolve();
        TypeDescription outer = typePool.describe(OUTER).resolve();
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(
                                ElementMatchers.named("save")
                                        .and(ElementMatchers.takesArguments(0)))
                        .isEmpty(),
                "method save()");
        requireDeclared(
                !outer.getDeclaredFields().filter(ElementMatchers.named("crcSave")).isEmpty(),
                "outer field ServerChunkLoader.crcSave");

        MethodDescription replacement =
                typePool.describe(SCRATCH)
                        .resolve()
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("crc"))
                        .getOnly();

        return builder.visit(
                MemberSubstitution.relaxed()
                        .field(
                                ElementMatchers.named("crcSave")
                                        .and(ElementMatchers.isDeclaredBy(outer)))
                        .onRead()
                        .replaceWith(replacement)
                        .on(ElementMatchers.named("save")));
    }

    private static void requireDeclared(boolean present, String member) {
        if (!present) {
            throw new IllegalStateException(
                    "SaveLoadedTaskCrcRacePatch: ServerChunkLoader$SaveLoadedTask no longer"
                            + " declares "
                            + member
                            + " — the name-string hook would silently no-op and reintroduce the"
                            + " ChunkChecksum dedup-cache race (redundant chunk writes and"
                            + " resends). Re-verify the patch against the current game source.");
        }
    }
}
