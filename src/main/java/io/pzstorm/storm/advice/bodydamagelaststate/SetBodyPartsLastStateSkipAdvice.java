package io.pzstorm.storm.advice.bodydamagelaststate;

import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Skips the body of {@code BodyDamage.setBodyPartsLastState()} on the dedicated server. The copy it
 * makes (18 body parts × 5 getters, every {@code IsoPlayer.postupdate}) feeds exactly one reader,
 * {@code IsoGameCharacter$Bandages.update}, whose body starts with {@code if (!GameServer.server)}
 * — so on the server the last-state array is written every tick and never read. 2.5% of player
 * update on ATF prod (scan #10, 2026-09-02).
 */
public class SetBodyPartsLastStateSkipAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return GameServer.server;
    }
}
