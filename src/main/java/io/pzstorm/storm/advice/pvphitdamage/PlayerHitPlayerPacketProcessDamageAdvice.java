package io.pzstorm.storm.advice.pvphitdamage;

import io.pzstorm.storm.patch.fixes.PvpHitsAlwaysDamage;
import java.util.List;
import net.bytebuddy.asm.Advice;

/** Runs on entry to {@code PlayerHitPlayerPacket.process}, after the server has rolled the hits. */
public class PlayerHitPlayerPacketProcessDamageAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Object packet, @Advice.FieldValue("hits") List<?> hits) {
        PvpHitsAlwaysDamage.beforeProcess(packet, hits);
    }
}
