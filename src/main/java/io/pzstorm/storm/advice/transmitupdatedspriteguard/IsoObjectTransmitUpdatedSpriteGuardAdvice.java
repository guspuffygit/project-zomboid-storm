package io.pzstorm.storm.advice.transmitupdatedspriteguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import net.bytebuddy.asm.Advice;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.network.GameServer;

/**
 * Advice for {@code IsoObject.transmitUpdatedSpriteToClients(UdpConnection)} that suppresses the
 * broadcast when the payload could not be applied by any receiver.
 *
 * <p>The packet identifies the sprite as {@code (sprite.id, spriteName)} and the client applies it
 * in {@code GameClient.receiveUpdateItemSprite} as {@code o.sprite =
 * IsoSpriteManager.instance.getSprite(id)} with a {@code setSprite(spriteName)} fallback only when
 * the name is non-empty. A runtime-created sprite (e.g. any {@code IsoWorldInventoryObject}'s) is
 * never registered in {@code IsoSpriteManager.intMap} and carries the default id 20000000 with a
 * null name, so broadcasting it permanently nulls the receiving object's sprite &mdash; and the
 * unguarded {@code this.sprite.hasNoTextures()} in {@code IsoWorldInventoryObject.render()} then
 * throws every frame for the rest of the client session, aborting the whole {@code FBORenderCell}
 * tile pass. A null sender sprite additionally NPEs here on the server after {@code startPacket()}.
 *
 * <p>Suppress exactly when the receiver would end up with a null sprite: the id does not resolve in
 * the server's own sprite registry (tiledefs are checksum-matched with clients, so server-side
 * resolvability predicts client-side resolvability) and the name fallback is empty. Resolvable
 * updates &mdash; fence damage, compost levels, grime removal &mdash; pass through unchanged.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class IsoObjectTransmitUpdatedSpriteGuardAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This IsoObject self) {
        if (!GameServer.server) {
            return false;
        }
        IsoSprite sprite = self.sprite;
        if (sprite != null && IsoSpriteManager.instance.getSprite(sprite.id) != null) {
            return false;
        }
        String name = self.spriteName;
        if (sprite != null && name != null && !name.isEmpty()) {
            return false;
        }
        LOGGER.warn(
                "IsoObjectTransmitUpdatedSpriteGuardPatch: suppressed unresolvable sprite"
                        + " broadcast object={} spriteId={} spriteName={} square={},{},{}",
                self.getClass().getSimpleName(),
                sprite == null ? "null" : String.valueOf(sprite.id),
                name,
                self.square == null ? -1 : self.square.getX(),
                self.square == null ? -1 : self.square.getY(),
                self.square == null ? -1 : self.square.getZ());
        return true;
    }
}
