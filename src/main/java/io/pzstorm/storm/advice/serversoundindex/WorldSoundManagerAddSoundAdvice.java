package io.pzstorm.storm.advice.serversoundindex;

import io.pzstorm.storm.sound.StormRepeatingSoundCoalescer;
import io.pzstorm.storm.sound.StormServerChunkSoundIndex;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import zombie.network.GameServer;

/**
 * Advice on the 13-arg body overload of {@code WorldSoundManager.addSound}. Only the body overload
 * is advised — the other {@code addSound} overloads delegate to it, so advising by bare name would
 * process each sound multiple times.
 *
 * <p>Enter: {@link StormRepeatingSoundCoalescer#tryCoalesce} — a non-null result is a live
 * repeating slot refreshed in place; the vanilla body (pool allocation, list append, chunk
 * indexing) is skipped and the exit advice returns the slot sound to the caller, exactly what
 * vanilla would have returned a fresh copy for.
 *
 * <p>Exit (vanilla body ran): {@link StormServerChunkSoundIndex#onSoundAdded} indexes the new sound
 * into the per-chunk sound lists and registers repeating sounds as coalescer slots.
 */
public class WorldSoundManagerAddSoundAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(
            @Advice.This Object manager,
            @Advice.Argument(0) Object source,
            @Advice.Argument(1) int x,
            @Advice.Argument(2) int y,
            @Advice.Argument(3) int z,
            @Advice.Argument(4) int radius,
            @Advice.Argument(5) int volume,
            @Advice.Argument(6) float zombieIgnoreDist,
            @Advice.Argument(7) float stressMod,
            @Advice.Argument(9) boolean doSend,
            @Advice.Argument(11) boolean repeating,
            @Advice.Argument(12) short flags) {
        if (!GameServer.server) {
            return null;
        }
        return StormRepeatingSoundCoalescer.tryCoalesce(
                manager,
                source,
                x,
                y,
                z,
                radius,
                volume,
                zombieIgnoreDist,
                stressMod,
                doSend,
                repeating,
                flags);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Object coalesced,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object sound) {
        if (coalesced != null) {
            sound = coalesced;
            return;
        }
        if (GameServer.server) {
            StormServerChunkSoundIndex.onSoundAdded(sound);
        }
    }
}
