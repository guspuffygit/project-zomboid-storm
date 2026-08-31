package io.pzstorm.storm.advice.client.adminseeall;

import io.pzstorm.storm.client.StormAdminSeeAllAlphaGuard;
import net.bytebuddy.asm.Advice;

/**
 * Skips the body of {@code IsoObject.setTargetAlpha(int, float)} when the write would hide a remote
 * player from a "can see all" admin. Every other write falls through. {@code suppress =
 * Throwable.class} makes any advice failure resolve to {@code false} — vanilla write proceeds.
 */
public class AdminSeeAllTargetAlphaAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.This Object self,
            @Advice.Argument(0) int playerIndex,
            @Advice.Argument(1) float targetAlpha) {
        return StormAdminSeeAllAlphaGuard.shouldKeepVisible(self, playerIndex, targetAlpha);
    }
}
