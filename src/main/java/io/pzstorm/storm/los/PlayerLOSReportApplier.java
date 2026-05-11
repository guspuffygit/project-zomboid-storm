package io.pzstorm.storm.los;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.PlayerUpdateLOSAuthorityMetrics;
import java.util.Stack;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoSurvivor;
import zombie.characters.IsoZombie;
import zombie.characters.Stats;
import zombie.core.math.PZMath;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.areas.IsoRoom;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/**
 * Replays the server-relevant slice of {@code IsoPlayer.updateLOS} against a cached client report,
 * skipping the vanilla {@code cell.getObjectList()} O(N) iteration entirely.
 *
 * <p>The kept side effects (per {@code docs/LOS_OPTIMIZATION_FINDINGS.md} Tier 1A):
 *
 * <ul>
 *   <li>{@code spottedList} clear + repopulate (self + visible characters)
 *   <li>{@code stats.numVisibleZombies} reset + counter increments (drives BodyDamage panic)
 *   <li>{@code numChasingZombies} reset (carried over via {@code setLastNumberChasingZombies})
 *   <li>{@code numSurvivorsInVicinity} reset + increments (drives BodyDamage boredom)
 *   <li>{@code TestZombieSpotPlayer} on the lit branch (authoritative aggro)
 *   <li>{@code TestZombieSpotPlayer} on the could-see-but-not-lit branch (aggro fallback)
 *   <li>{@code lastSpotted} maintenance + {@code clearSpottedTimer} decrement
 * </ul>
 *
 * Server-dead behaviours from vanilla (music tension, alpha writes, {@code timeSinceLastStab},
 * surprised-sound, {@code lastNumVisibleZombies}) are skipped — see findings doc.
 *
 * <p>The {@code canSee}/{@code couldSee} bits come from the client; everything else (positions,
 * room, ghost/fakeDead state, lastSpotted membership) is read from authoritative server state.
 * Distance is recomputed server-side to keep the visibility gate from depending on client-supplied
 * numbers.
 */
public final class PlayerLOSReportApplier {

    private PlayerLOSReportApplier() {}

    public static void apply(IsoPlayer player, PlayerLOSReportCache.Report report) {
        Stats stats = player.getStats();
        Stack<IsoMovingObject> spottedList = player.getSpottedList();
        Stack<IsoMovingObject> lastSpotted = player.getLastSpotted();

        spottedList.clear();
        stats.numVisibleZombies = 0;
        stats.setLastNumberChasingZombies(stats.numChasingZombies);
        stats.numChasingZombies = 0;
        player.setNumSurvivorsInVicinity(0);

        IsoGridSquare playerSquare = player.getCurrentSquare();
        if (playerSquare == null) {
            return;
        }

        if (report.selfSpotted) {
            spottedList.add(player);
        }

        float locX = player.getX();
        float locY = player.getY();
        float locZ = player.getZ();
        IsoRoom playerRoom = playerSquare.getRoom();

        int n = report.ids.length;
        int survivorsLocal = 0;
        int resolveFailures = 0;
        boolean visionLoaded = false;
        boolean asleep = false;
        float detectionRange = 0f;

        for (int i = 0; i < n; i++) {
            short id = report.ids[i];
            IsoMovingObject obj = resolve(id);
            if (obj == null) {
                resolveFailures++;
                continue;
            }
            if (obj == player) {
                continue;
            }

            IsoGridSquare objSquare = obj.getCurrentSquare();
            if (objSquare == null) {
                continue;
            }

            float dx = obj.getX() - locX;
            float dy = obj.getY() - locY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            boolean couldSee = report.couldSee[i];
            boolean canSee = report.canSee[i];
            if (!visionLoaded) {
                visionLoaded = true;
                asleep = player.isAsleep();
                detectionRange = player.getDetectionRange();
            }
            boolean lit = !asleep && (canSee || (dist < detectionRange && couldSee));

            if (lit) {
                player.TestZombieSpotPlayer(obj);

                if (!(obj instanceof IsoGameCharacter character)) {
                    continue;
                }

                if (character instanceof IsoSurvivor) {
                    survivorsLocal++;
                }

                if (character instanceof IsoZombie zombie) {
                    float objZ = obj.getZ();
                    IsoRoom objRoom = objSquare.getRoom();
                    if (objZ >= locZ - 1.0F
                            && dist < 7.0F
                            && !zombie.ghost
                            && !zombie.isFakeDead()
                            && objRoom == playerRoom) {
                        stats.numVisibleZombies++;
                    }

                    float maxdist = stats.numVisibleZombies > 4 ? 7.0F : 4.0F;
                    if (!lastSpotted.contains(character)
                            && dist < maxdist
                            && PZMath.fastfloor(objZ) == PZMath.fastfloor(locZ)) {
                        stats.numVisibleZombies += 2;
                    }
                }

                spottedList.add(character);
            } else if (couldSee) {
                player.TestZombieSpotPlayer(obj);
            }
        }

        if (survivorsLocal > 0) {
            player.setNumSurvivorsInVicinity(survivorsLocal);
        }

        int actualSpotted = 0;
        for (int i = 0; i < spottedList.size(); i++) {
            IsoMovingObject sp = spottedList.get(i);
            if (!lastSpotted.contains(sp)) {
                lastSpotted.add(sp);
            }
            if (sp instanceof IsoZombie) {
                actualSpotted++;
            }
        }

        int spottedTimer = player.getClearSpottedTimer();
        if (spottedTimer <= 0 && actualSpotted == 0) {
            lastSpotted.clear();
            player.setClearSpottedTimer(1000);
        } else {
            player.setClearSpottedTimer(spottedTimer - 1);
        }

        if (resolveFailures > 0) {
            PlayerUpdateLOSAuthorityMetrics.recordResolveFailures(resolveFailures);
            if (StormLogger.LOGGER.isDebugEnabled()) {
                StormLogger.LOGGER.debug(
                        "[LOSAuthority] player={} clientTick={} resolveFailures={} / entries={}",
                        report.playerOnlineID,
                        report.clientTick,
                        resolveFailures,
                        n);
            }
        }
    }

    /**
     * Resolves a client-reported {@code onlineID} to its server-side {@link IsoMovingObject}.
     * Players and zombies share the short namespace via separate maps, so player wins on tie (lower
     * IDs in vanilla are reserved for connected players).
     */
    static IsoMovingObject resolve(short id) {
        IsoPlayer player = GameServer.IDToPlayerMap.get(id);
        if (player != null) {
            return player;
        }
        IsoZombie zombie = ServerMap.instance.zombieMap.get(id);
        if (zombie != null) {
            return zombie;
        }
        return null;
    }
}
