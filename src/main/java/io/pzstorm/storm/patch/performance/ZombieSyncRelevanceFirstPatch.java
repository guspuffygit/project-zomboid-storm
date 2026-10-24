package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Makes {@code NetworkZombiePacker.getZombieData(UdpConnection, ZombieSynchronizationPacket)} test
 * a zombie's position against the connection before it probes the zombie's owner, see {@code
 * StormZombieSyncRelevance} for the cost and the outcome-identity argument.
 *
 * <p>Two weaves on that one method: an enter/exit advice records the connection argument, and a
 * member substitution routes both of its {@code IsoZombie.getOwner()} calls through {@code
 * StormZombieSyncRelevance.ownerIfRelevant}. The vanilla body is otherwise untouched, including the
 * first loop (requested full updates, which never calls {@code getOwner}).
 *
 * <p>Always on, server-only by registration gate. Fails soft: any exception in the substitute turns
 * it into plain {@code getOwner()} for the session.
 *
 * <p>Re-validate on game update: the second loop of {@code getZombieData} (NetworkZombiePacker.java
 * :180 in 42.21.0) must still guard its send on {@code getOwner() != null && getOwner() !=
 * connection && connection.RelevantTo(getX(), getY(), (getRelevantRange() - 2) * 10)} and the
 * method must still take the packed connection as its first argument. If the condition changes,
 * {@code ownerIfRelevant} must change with it or be dropped.
 */
public class ZombieSyncRelevanceFirstPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.popman.NetworkZombiePacker";
    private static final String ZOMBIE = "zombie.characters.IsoZombie";
    private static final String HELPER = "io.pzstorm.storm.zombie.StormZombieSyncRelevance";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.zombiesyncrelevance.NetworkZombiePackerGetZombieDataAdvice";

    public ZombieSyncRelevanceFirstPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        ElementMatcher.Junction<MethodDescription> getZombieData =
                ElementMatchers.named("getZombieData").and(ElementMatchers.takesArguments(2));
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods().filter(getZombieData).isEmpty()) {
            throw new IllegalStateException(
                    "ZombieSyncRelevanceFirstPatch: NetworkZombiePacker no longer declares"
                            + " getZombieData(UdpConnection, ZombieSynchronizationPacket). Re-verify"
                            + " the patch against the current game source.");
        }
        MethodDescription substitute =
                typePool.describe(HELPER)
                        .resolve()
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("ownerIfRelevant"))
                        .getOnly();
        return builder.visit(
                        Advice.to(typePool.describe(ADVICE).resolve(), locator).on(getZombieData))
                .visit(
                        MemberSubstitution.relaxed()
                                .method(
                                        ElementMatchers.named("getOwner")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(
                                                        ElementMatchers.isDeclaredBy(
                                                                ElementMatchers.named(ZOMBIE))))
                                .replaceWith(substitute)
                                .on(getZombieData));
    }
}
