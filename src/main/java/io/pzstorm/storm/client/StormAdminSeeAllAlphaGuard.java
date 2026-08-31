package io.pzstorm.storm.client;

import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.network.GameClient;

/**
 * Gate for {@link io.pzstorm.storm.patch.client.IsoObjectAdminSeeAllTargetAlphaPatch}: decides
 * whether a {@code targetAlpha = 0} write against a remote player must be dropped because the local
 * player at {@code playerIndex} is an admin with "can see all" enabled.
 *
 * <p>Vanilla {@code IsoPlayer.render} sets a remote player's targetAlpha to 1 every frame for such
 * admins ({@code checkCanSeeClient}), while the local player's {@code updateLOS} sets it to 0 every
 * tick for occluded remotes within 20 tiles. The alpha lerp runs in update, so whichever of the two
 * ran last before the remote's own update wins — players whose objectList index is after the local
 * player's stay at alpha 0 with a floating name tag. Mirroring the render rule here makes both
 * writers agree.
 */
public final class StormAdminSeeAllAlphaGuard {

    private StormAdminSeeAllAlphaGuard() {}

    public static boolean shouldKeepVisible(Object self, int playerIndex, float targetAlpha) {
        if (targetAlpha != 0.0F || !GameClient.client) {
            return false;
        }
        if (!(self instanceof IsoPlayer remote) || self instanceof IsoAnimal) {
            return false;
        }
        if (playerIndex < 0 || playerIndex >= IsoPlayer.numPlayers) {
            return false;
        }
        IsoPlayer local = IsoPlayer.players[playerIndex];
        return local != null
                && local != remote
                && !remote.isLocalPlayer()
                && local.canSeeAll()
                && !local.isAccessLevel("None");
    }
}
