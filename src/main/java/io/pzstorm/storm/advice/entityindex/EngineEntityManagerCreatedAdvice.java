package io.pzstorm.storm.advice.entityindex;

import io.pzstorm.storm.entity.StormEntityIndex;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Constructor-exit hook on {@code EngineEntityManager}: hands the freshly-constructed manager to
 * {@link StormEntityIndex#onManagerCreated(Object)} so the index tracks the new (empty) global
 * entity array. A new manager is created once per world init ({@code GameEntityManager.Init}), so
 * this also resets the index across world reloads.
 */
public class EngineEntityManagerCreatedAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object manager) {
        if (!GameServer.server) {
            return;
        }
        StormEntityIndex.onManagerCreated(manager);
    }
}
