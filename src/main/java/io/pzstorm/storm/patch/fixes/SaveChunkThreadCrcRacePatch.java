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
 * Fixes the shared-{@code CRC32} race in {@code ServerChunkLoader$SaveChunkThread.addLoadedJob}.
 *
 * <p>{@code addLoadedJob} serializes the chunk on the <b>calling</b> thread before queueing the
 * result, passing the thread object's single shared {@code crc32} field into {@code
 * IsoChunk.SaveLoadedChunk} → {@code IsoChunk.Save}, which finishes with a reset/update/getValue
 * sequence and embeds the value in the chunk file header. During {@code ServerMap.SaveAll} with
 * &ge;10 loaded cells, four {@code WorkerThread}s run {@code ServerCell.Save(true)} → {@code
 * addLoadedJob} concurrently, interleaving on that one {@code CRC32} — a fraction of every periodic
 * save's chunks get a garbage embedded checksum, and each later load of those chunks logs "CRC
 * mismatch" from {@code sanityCheck.checkCRC} (log-only; the chunk bytes are task-local and
 * correct, so no data is harmed — but the map log fills with alarming noise after every save).
 *
 * <p>The substitution redirects the {@code crc32} field read inside {@code addLoadedJob} to {@link
 * io.pzstorm.storm.map.StormChunkSaveCrc#crc(Object)}, a per-thread instance. The constructor's
 * field write is untouched (the now-unread field stays initialized). See {@code
 * SaveLoadedTaskCrcRacePatch} for the sibling race on the outer {@code crcSave}.
 *
 * <p>Fail-loud: the hook is name-string based, so a vanilla rename would otherwise silently no-op
 * and reintroduce the race. {@link #dynamicType} throws if {@code addLoadedJob} or {@code crc32}
 * are no longer declared — re-verify against the game source on update.
 *
 * <p>Registration-gated to the dedicated server ({@code StormEnv.isStormServer()}).
 */
public class SaveChunkThreadCrcRacePatch extends StormClassTransformer {

    private static final String SCRATCH = "io.pzstorm.storm.map.StormChunkSaveCrc";

    public SaveChunkThreadCrcRacePatch() {
        super("zombie.network.ServerChunkLoader$SaveChunkThread");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(className).resolve();
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(ElementMatchers.named("addLoadedJob"))
                        .isEmpty(),
                "method addLoadedJob");
        requireDeclared(
                !target.getDeclaredFields().filter(ElementMatchers.named("crc32")).isEmpty(),
                "field crc32");

        MethodDescription replacement =
                typePool.describe(SCRATCH)
                        .resolve()
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("crc"))
                        .getOnly();

        return builder.visit(
                MemberSubstitution.relaxed()
                        .field(
                                ElementMatchers.named("crc32")
                                        .and(ElementMatchers.isDeclaredBy(target)))
                        .onRead()
                        .replaceWith(replacement)
                        .on(ElementMatchers.named("addLoadedJob")));
    }

    private static void requireDeclared(boolean present, String member) {
        if (!present) {
            throw new IllegalStateException(
                    "SaveChunkThreadCrcRacePatch: ServerChunkLoader$SaveChunkThread no longer"
                            + " declares "
                            + member
                            + " — the name-string hook would silently no-op and reintroduce the"
                            + " embedded-checksum race behind the \"CRC mismatch\" log spam."
                            + " Re-verify the patch against the current game source.");
        }
    }
}
