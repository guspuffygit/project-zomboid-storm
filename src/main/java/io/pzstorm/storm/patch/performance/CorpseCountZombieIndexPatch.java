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
 * Serves the {@code ZombieHealthImpact} zombie scan in {@code CorpseCount.getCorpseCount(int, int,
 * int, IsoBuilding)} from the shared spatial index instead of vanilla's 25×25-square walk (625
 * {@code getGridSquare} lookups plus a {@code movingObjects} scan per square, per player, per tick
 * from {@code BodyDamage.UpdateIllness} — ~5.4 ms/tick at 128 players). Two coordinated changes
 * (see {@code StormCorpseZombieCount} for semantics and the failure fallback):
 *
 * <ol>
 *   <li>{@code MemberSubstitution} redirects the single {@code zombieHealthImpact.getValue()} call
 *       in the 4-arg {@code getCorpseCount} to {@code
 *       StormCorpseZombieCount.readZombieHealthImpact()}, which returns {@code false} while the
 *       fast path is healthy (skipping the vanilla walk) and the real option value otherwise.
 *   <li>Exit advice on the same method adds the index-derived zombie count to the return value when
 *       the fast path served the call.
 * </ol>
 *
 * <p>Always on; vanilla behavior is restored automatically and permanently if the fast path ever
 * throws, and per-call whenever the index has no snapshot for the current frame. Server-only by
 * registration gate. Re-validate on game update: the 4-arg {@code getCorpseCount} overload must
 * remain the only body (the 1-arg overload delegates to it), its {@code
 * zombieHealthImpact.getValue()} read must remain the only 0-arg {@code getValue} call in the
 * method, and the zombie loop must keep the {@code dx/dy -12..12} box, the {@code building ==
 * sq.getBuilding()} match, and the {@code count >= maxCorpseCount} early return
 * (CorpseCount.java:80-99 in 42.20.3).
 */
public class CorpseCountZombieIndexPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.CorpseCount";
    private static final String HELPER = "io.pzstorm.storm.spatial.StormCorpseZombieCount";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.corpsecountzombies.CorpseCountGetCorpseCountAdvice";

    public CorpseCountZombieIndexPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                        .filter(
                                ElementMatchers.named("getCorpseCount")
                                        .and(ElementMatchers.takesArguments(4)))
                        .size()
                != 1) {
            throw new IllegalStateException(
                    "CorpseCountZombieIndexPatch: CorpseCount no longer declares exactly one 4-arg"
                            + " getCorpseCount — the name-string hooks would silently no-op or"
                            + " apply to the wrong overload, either reintroducing the 25×25 zombie"
                            + " scan or double-counting. Re-verify the patch against the current"
                            + " game source.");
        }
        MethodDescription readZombieHealthImpact =
                typePool.describe(HELPER)
                        .resolve()
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("readZombieHealthImpact"))
                        .getOnly();
        builder =
                builder.visit(
                        MemberSubstitution.relaxed()
                                .method(
                                        ElementMatchers.named("getValue")
                                                .and(ElementMatchers.takesArguments(0)))
                                .replaceWith(readZombieHealthImpact)
                                .on(
                                        ElementMatchers.named("getCorpseCount")
                                                .and(ElementMatchers.takesArguments(4))));
        builder =
                builder.visit(
                        Advice.to(typePool.describe(ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("getCorpseCount")
                                                .and(ElementMatchers.takesArguments(4))));
        return builder;
    }
}
