package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Serves {@code WorldSoundManager}'s per-listener sound queries from the per-chunk sound index on a
 * dedicated server instead of the global {@code soundList} scan. Vanilla builds and reads the
 * per-chunk index only on the client, so every animal's per-tick {@code getSoundAnimal} and every
 * zombie {@code getSoundZomb}/{@code getBiggestSoundZomb} query walks all live sounds in the world.
 * Five coordinated changes on {@code zombie.WorldSoundManager} (see {@code
 * StormServerChunkSoundIndex} for the index semantics and failure fallback, and {@code
 * StormRepeatingSoundCoalescer} for the repeating-sound dedup semantics):
 *
 * <ol>
 *   <li>Enter advice on the 13-arg body overload of {@code addSound} coalesces a matching repeating
 *       emission into its source's live slot sound (skipping the vanilla body); exit advice indexes
 *       each genuinely new sound into the chunks of its client-identical hearing footprint (via
 *       {@code ServerMap.getChunk}) and registers repeating sounds as coalescer slots.
 *   <li>Enter advice on {@code update()} un-indexes the sounds vanilla is about to remove and
 *       release to its object pool that tick.
 *   <li>Enter advice on {@code KillCell()} un-indexes everything on world teardown.
 *   <li>Enter advice on {@code getStressFromSounds} replaces the additive loop with {@code
 *       StormRepeatingSoundCoalescer.stressFromSounds}, which weights each coalesced slot by the
 *       copy count vanilla would have alive so stress accumulation is unchanged by coalescing.
 *   <li>{@code MemberSubstitution} redirects the single {@code GameServer.server} field read in
 *       each of {@code getSoundZomb}, {@code getSoundAnimal} and {@code getBiggestSoundZomb} to
 *       {@code StormServerChunkSoundIndex.readServerFlag()}, steering them onto the vanilla client
 *       branch (chunk sound lists) — or back to the vanilla global scan permanently if indexing has
 *       failed.
 * </ol>
 *
 * <p>Server-only by registration gate. Re-validate on game update: the addSound body overload is
 * matched by name + 13 parameters, {@code addSound} must remain the only writer of the sound lists,
 * each read method must contain exactly one {@code GameServer.server} read with the {@code chunk !=
 * null && !GameServer.server} shape, {@code getStressFromSounds} must keep the {@code stresshumans
 * && radius != 0} Manhattan-distance sum its replacement replicates, and {@code
 * IsoChunk.updateSounds()} must stay client-only (WorldSoundManager.java:134/160/235/258/301/428 in
 * 42.20.3).
 */
public class WorldSoundServerChunkIndexPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.WorldSoundManager";
    private static final String INDEX = "io.pzstorm.storm.sound.StormServerChunkSoundIndex";
    private static final String PKG = "io.pzstorm.storm.advice.serversoundindex.";

    public WorldSoundServerChunkIndexPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        requireDeclared(
                target.getDeclaredMethods()
                                .filter(
                                        ElementMatchers.named("addSound")
                                                .and(ElementMatchers.takesArguments(13)))
                                .size()
                        == 1,
                "exactly one 13-arg addSound body overload");
        requireDeclared(
                !target.getDeclaredMethods().filter(ElementMatchers.named("update")).isEmpty(),
                "update");
        requireDeclared(
                !target.getDeclaredMethods().filter(ElementMatchers.named("KillCell")).isEmpty(),
                "KillCell");
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(ElementMatchers.named("getSoundZomb"))
                        .isEmpty(),
                "getSoundZomb");
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(ElementMatchers.named("getSoundAnimal"))
                        .isEmpty(),
                "getSoundAnimal");
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(ElementMatchers.named("getBiggestSoundZomb"))
                        .isEmpty(),
                "getBiggestSoundZomb");
        requireDeclared(
                target.getDeclaredMethods()
                                .filter(
                                        ElementMatchers.named("getStressFromSounds")
                                                .and(ElementMatchers.takesArguments(3)))
                                .size()
                        == 1,
                "exactly one 3-arg getStressFromSounds");

        MethodDescription readServerFlag =
                typePool.describe(INDEX)
                        .resolve()
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("readServerFlag"))
                        .getOnly();
        TypeDescription gameServer = typePool.describe("zombie.network.GameServer").resolve();

        builder =
                builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "WorldSoundManagerAddSoundAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("addSound")
                                                .and(ElementMatchers.takesArguments(13))));
        builder =
                builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "WorldSoundManagerUpdateAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("update")));
        builder =
                builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "WorldSoundManagerKillCellAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("KillCell")));
        builder =
                builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "WorldSoundManagerGetStressAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("getStressFromSounds")
                                                .and(ElementMatchers.takesArguments(3))));
        builder =
                builder.visit(
                        MemberSubstitution.relaxed()
                                .field(
                                        ElementMatchers.named("server")
                                                .and(ElementMatchers.isDeclaredBy(gameServer)))
                                .onRead()
                                .replaceWith(readServerFlag)
                                .on(
                                        ElementMatchers.named("getSoundZomb")
                                                .or(ElementMatchers.named("getSoundAnimal"))
                                                .or(ElementMatchers.named("getBiggestSoundZomb"))));
        return builder;
    }

    private static void requireDeclared(boolean present, String member) {
        if (!present) {
            throw new IllegalStateException(
                    "WorldSoundServerChunkIndexPatch: WorldSoundManager no longer declares "
                            + member
                            + " — the name-string hooks would silently no-op, leaving the index"
                            + " partially wired (indexed but unread, or read but never purged)."
                            + " Re-verify the patch against the current game source.");
        }
    }
}
