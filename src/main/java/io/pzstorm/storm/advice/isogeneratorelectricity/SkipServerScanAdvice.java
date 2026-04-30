package io.pzstorm.storm.advice.isogeneratorelectricity;

import io.pzstorm.storm.logging.StormLogger;
import net.bytebuddy.asm.Advice;
import zombie.SandboxOptions;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoGenerator;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/**
 * Advice for {@code IsoGenerator.update()}.
 *
 * <p>On a dedicated server, replaces the per-tick {@code setSurroundingElectricity()} call with the
 * cheap chunk-position bookkeeping subset only &mdash; skipping the (2R+1)&sup2; &times; Z box of
 * grid-square iteration plus 11 instanceof checks per IsoObject that dominates main-thread CPU
 * usage when many generators are active.
 *
 * <p>Why skipping the inner scan is safe on a server:
 *
 * <ul>
 *   <li>{@code IsoObject.checkHaveElectricity()} (called per object in the original scan) is
 *       already a no-op on a server &mdash; it bails on the very first line with {@code if
 *       (!GameServer.server)}.
 *   <li>{@code itemsPowered} (a {@code HashMap<String,String>} of human-readable UI labels) is only
 *       consumed by {@code ISGeneratorInfoWindow.lua} on the client &mdash; the server never reads
 *       it per tick.
 *   <li>{@code totalPowerUsing} is only consumed by the hourly fuel-consumption loop in {@code
 *       update()}. The other callers of {@code setSurroundingElectricity} ({@code setActivated},
 *       {@code syncIsoObjectReceive}) are not patched, so the value is still refreshed whenever the
 *       generator is turned on/off. It may drift between activations as items in range change state
 *       &mdash; an accepted approximation, addressed by S2.
 *   <li>The chunk-position bookkeeping ({@code chunk.addGeneratorPos / removeGeneratorPos}) drives
 *       {@code IsoGridSquare.haveElectricity()} via {@code IsoChunk.isGeneratorPoweringSquare} and
 *       is preserved exactly by re-implementing it here.
 * </ul>
 *
 * <p>Patching {@code update()} (rather than {@code setSurroundingElectricity} itself) keeps the
 * activation-change call sites on the original full path so they still recompute fuel consumption
 * correctly.
 *
 * <p>{@code generatorRadius} is read directly from sandbox options instead of via
 * {@code @Advice.FieldValue} on the private static &mdash; same source of truth, public API, no
 * Byte Buddy field-binding edge cases.
 *
 * <p>No lambdas / streams &mdash; advice bodies are inlined into the target method and must be
 * plain imperative Java.
 */
public class SkipServerScanAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.This IsoGenerator self,
            @Advice.FieldValue(value = "updateSurrounding", readOnly = false)
                    boolean updateSurrounding) {
        if (!GameServer.server) {
            return;
        }
        if (!updateSurrounding) {
            return;
        }
        IsoGridSquare square = self.getSquare();
        if (square == null) {
            return;
        }
        IsoChunk myChunk = square.chunk;
        if (myChunk == null) {
            return;
        }

        int generatorRadius = SandboxOptions.getInstance().generatorTileRange.getValue();
        int generatorChunkRange = generatorRadius / 10 + 1;

        int chunkX = myChunk.wx;
        int chunkY = myChunk.wy;
        boolean activated = self.isActivated();
        int sx = square.x;
        int sy = square.y;
        int sz = square.z;

        int chunksTouched = 0;
        int chunksUpdated = 0;
        for (int dy = -generatorChunkRange; dy <= generatorChunkRange; dy++) {
            for (int dx = -generatorChunkRange; dx <= generatorChunkRange; dx++) {
                IsoChunk chunk = ServerMap.instance.getChunk(chunkX + dx, chunkY + dy);
                if (chunk == null) {
                    continue;
                }
                chunksTouched++;
                // Inline IsoGenerator.touchesChunk(chunk) — private, can't call from advice.
                int minX = chunk.wx * 8;
                int minY = chunk.wy * 8;
                int maxX = minX + 7;
                int maxY = minY + 7;
                if (sx - generatorRadius > maxX) {
                    continue;
                }
                if (sx + generatorRadius < minX) {
                    continue;
                }
                if (sy - generatorRadius > maxY) {
                    continue;
                }
                if (sy + generatorRadius < minY) {
                    continue;
                }

                if (activated) {
                    chunk.addGeneratorPos(sx, sy, sz);
                } else {
                    chunk.removeGeneratorPos(sx, sy, sz);
                }
                chunksUpdated++;
            }
        }

        StormLogger.LOGGER.trace(
                "IsoGeneratorElectricityPatch: gen at ({},{},{}) activated={} radius={}"
                        + " chunkRange={} chunksTouched={} chunksUpdated={}",
                sx,
                sy,
                sz,
                activated,
                generatorRadius,
                generatorChunkRange,
                chunksTouched,
                chunksUpdated);

        updateSurrounding = false;
    }
}
