package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.characters.IsoGameCharacter} by {@code
 * IsoGameCharacterEcsMemoPatch} (the redefinition adds a {@code stormEcsMemo} field plus this
 * accessor pair), so each character carries a tiny per-instance memo for {@code
 * ECSEntity.tryGetECSComponent} lookups — the {@code getECSClass} walk plus {@code HashMap} probe
 * behind {@code getOwner}/{@code getOwnerPlayer}/{@code getStateMachineComponent}/{@code
 * getFrameKeeper} was ~1.5% of server main combined (ATF profile 2026-08-26, 135 players).
 *
 * <p>Layout: an 8-slot {@code Object[]} of {@code Object[2]} pairs {@code {requestedClass,
 * component}}, slot-addressed by {@code (identityHashCode(class) >>> 4) & 7}. Pairs are immutable
 * once published, so a hit is two reads plus an ownership re-validation ({@code
 * component.getECSOwnerEntity() == entity} ⟺ the component is still registered on the entity —
 * {@code removeECSComponent} nulls the owner, {@code setECSComponent} re-stamps it), and the memo
 * can never return a component the vanilla map no longer holds. The field lives only on {@code
 * IsoGameCharacter} — not on {@code IsoObject}, which also implements {@code ECSEntity} but exists
 * in the millions; non-character entities fall through to the vanilla lookup via an {@code
 * instanceof} check in the advice.
 *
 * <p>The field is volatile and pairs are freshly allocated per store, so a racy cross-thread read
 * sees either the correct value or {@code null} in a pair slot (guarded in the advice) — never a
 * torn pair.
 */
public interface StormEcsMemoHolder {

    Object[] getStormEcsMemo();

    void setStormEcsMemo(Object[] memo);
}
