package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Attaches enter/exit advice to {@code zombie.iso.objects.IsoHutch.update()} that replaces the
 * per-tick hutch/nest-box dirt accrual with a game-time accrual at the metagame rate. The actual
 * fix logic lives in {@link HutchDirtRateFix}.
 *
 * <h2>The bug this patches</h2>
 *
 * <p>Vanilla rolls hutch floor dirt ({@code Rand.NextBool(8000 - 100 * animalsInside)}) once per
 * world tick and nest-box dirt ({@code Rand.NextBool(300)}) once per tick per nesting hen, with no
 * {@code GameTime} scaling — so the dirt rate is proportional to the tick rate and, in MP, runs
 * around the clock whenever anyone streams the chunk. The intended rate, visible in {@code
 * IsoHutch.doMeta(int)}, is one 1-in-{@code min(25 - animals, 10)} roll per <i>game</i> hour.
 * Loaded coops on a dedicated server hit the 20-dirt heal cutoff within a few real hours and kill
 * the birds within a real day or two; players report having to clean several times per real day.
 *
 * <h2>Why enter/exit advice on {@code update()} and not the roll sites</h2>
 *
 * <p>The rolls are mid-method statements (one in {@code update()}, one in the private {@code
 * updateAnimalInside} it calls), so a boundary advice cannot suppress them directly. Instead the
 * exit advice diffs the dirt values against an entry snapshot, reverts whatever the per-tick rolls
 * added, and applies Storm's own hourly roll — every other side effect of the vanilla body
 * (healing, dirt damage, egg laying, sync) is untouched. A throw anywhere in the fix latches it off
 * permanently and vanilla accrual resumes.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers} — hutch simulation is server-authoritative
 * in MP ({@code isOwner()} is {@code !GameClient.client}); clients mirror dirt via {@code sync()}.
 * The accrual rate is tunable live via the {@code Storm.HutchDirtRatePercent} sandbox option.
 */
public class HutchDirtRateFixPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.hutchdirtrate.";

    public HutchDirtRateFixPatch() {
        super("zombie.iso.objects.IsoHutch");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "HutchDirtRateAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
