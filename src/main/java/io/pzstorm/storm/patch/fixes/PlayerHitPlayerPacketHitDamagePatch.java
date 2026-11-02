package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server only. Entry advice on {@code PlayerHitPlayerPacket.process} that hands the rolled hits to
 * {@link PvpHitsAlwaysDamage}, which clears the failed-roll {@code isIgnoreDamage} flag while the
 * {@code Storm.PvpHitsAlwaysDamage} sandbox option is on.
 *
 * <p>Fail-soft: the advice suppresses its own errors, and the helper goes inert and logs when a
 * field is missing, so hits fall back to vanilla. Re-validate on game updates. The patch assumes
 * {@code HitCharacter.processClient} runs {@code preProcess} (which calls {@code Hit.roll}) before
 * {@code process}, that {@code PlayerHit.hits} holds the {@code Hit}s, and that {@code Hit.process}
 * still passes {@code hitData.isIgnoreDamage()} to {@code IsoGameCharacter.Hit}.
 */
public class PlayerHitPlayerPacketHitDamagePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.network.packets.hit.PlayerHitPlayerPacket";
    private static final String HIT = "zombie.network.fields.hit.Hit";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.pvphitdamage.PlayerHitPlayerPacketProcessDamageAdvice";

    public PlayerHitPlayerPacketHitDamagePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("process").and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "PlayerHitPlayerPacketHitDamagePatch: PlayerHitPlayerPacket.process() is gone."
                            + " Re-verify against the current game source.");
        }
        if (typePool.describe(HIT)
                        .resolve()
                        .getDeclaredFields()
                        .filter(ElementMatchers.namedOneOf("isIgnoreDamage", "hitData"))
                        .size()
                != 2) {
            throw new IllegalStateException(
                    "PlayerHitPlayerPacketHitDamagePatch: Hit.isIgnoreDamage or Hit.hitData is"
                            + " gone. Re-verify how failed hit rolls reach the damage call.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("process")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
