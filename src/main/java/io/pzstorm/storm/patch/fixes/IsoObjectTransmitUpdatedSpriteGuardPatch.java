package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the client-poisoning UpdateItemSprite broadcast: {@code
 * IsoObject.transmitUpdatedSpriteToClients} sends {@code (sprite.id, spriteName)} plus a bare
 * object-list index, and {@code GameClient.receiveUpdateItemSprite} assigns {@code o.sprite =
 * getSprite(id)} unconditionally, falling back to the name only when non-empty. When the server
 * object's sprite is runtime-created (unregistered id 20000000, null name &mdash; every {@code
 * IsoWorldInventoryObject}), the receiving object's sprite becomes permanently null and the
 * unguarded dereference in {@code IsoWorldInventoryObject.render()} throws every frame, killing the
 * client's whole {@code FBORenderCell} tile pass for the rest of the session:
 *
 * <pre>NullPointerException: Cannot invoke "IsoSprite.hasNoTextures()" because "this.sprite" is
 *     null at IsoWorldInventoryObject.render &rarr; FBORenderCell.renderWorldInventoryObject</pre>
 *
 * <p>Reported live via launcher bundle {@code shytvlwzcv} ("game break when chopping logs") and
 * unrecoverable in-session; only a reconnect rebuilds the object. Fixing the sender protects
 * vanilla clients too and also removes a server-side NPE-after-{@code startPacket()} when the
 * sprite is null. See {@link
 * io.pzstorm.storm.advice.transmitupdatedspriteguard.IsoObjectTransmitUpdatedSpriteGuardAdvice} for
 * the exact suppress condition; resolvable sprite updates pass through unchanged.
 *
 * <p>Advice on the 1-arg overload covers the no-arg delegate and the {@code
 * GameServer.receiveUpdateItemSprite} relay. Server-only by registration gate; the advice also
 * guards on {@code GameServer.server} at runtime.
 */
public class IsoObjectTransmitUpdatedSpriteGuardPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.IsoObject";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.transmitupdatedspriteguard"
                    + ".IsoObjectTransmitUpdatedSpriteGuardAdvice";

    public IsoObjectTransmitUpdatedSpriteGuardPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("transmitUpdatedSpriteToClients")
                                .and(ElementMatchers.takesArguments(1)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoObjectTransmitUpdatedSpriteGuardPatch: IsoObject no longer declares the"
                            + " 1-arg transmitUpdatedSpriteToClients — the name-string hook would"
                            + " silently no-op and reintroduce the null-sprite client poison."
                            + " Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("transmitUpdatedSpriteToClients")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
