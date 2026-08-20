package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;

/**
 * Prometheus instruments for Storm's orphaned-{@code AnimationPlayer} sweep (see {@code
 * io.pzstorm.storm.patch.performance.StormAnimationPlayerSweep}).
 *
 * <ul>
 *   <li>{@code storm_animation_players_in_use} — animation players checked out of the pool. Should
 *       track the number of animated characters in the world; a value that climbs while population
 *       is flat is the leak.
 *   <li>{@code storm_animation_players_stranded} — checked-out players whose character no longer
 *       points back at them. The sweep cannot reclaim these through the character API; a non-zero
 *       value means there is a second leak path to find.
 *   <li>{@code storm_animation_players_reclaimed_total} — players released back to the pool, by
 *       holder class.
 *   <li>{@code storm_recently_removed_players} — size of {@code IsoPlayer.RecentlyRemoved}, which
 *       vanilla only drains client-side.
 *   <li>{@code storm_recently_removed_players_drained_total} — entries dropped from that list.
 * </ul>
 */
public final class StormAnimationPlayerSweepMetrics {

    private static final Gauge IN_USE =
            Gauge.builder()
                    .name("storm_animation_players_in_use")
                    .help(
                            "AnimationPlayer instances checked out of the pool across all threads."
                                    + " Tracks animated characters in the world; growth against a flat"
                                    + " population is the orphan leak.")
                    .register(StormPrometheus.registry());

    private static final Gauge STRANDED =
            Gauge.builder()
                    .name("storm_animation_players_stranded")
                    .help(
                            "Checked-out AnimationPlayers whose character no longer references"
                                    + " them. Not reclaimable via IsoGameCharacter.releaseAnimationPlayer;"
                                    + " non-zero means an unknown leak path.")
                    .register(StormPrometheus.registry());

    private static final Gauge OUT_OF_WORLD =
            Gauge.builder()
                    .name("storm_animation_players_out_of_world")
                    .help(
                            "Checked-out AnimationPlayers held by characters that have left the"
                                    + " world but have not yet aged past"
                                    + " -Dstorm.animplayer.sweep.graceMs.")
                    .register(StormPrometheus.registry());

    private static final Counter RECLAIMED =
            Counter.builder()
                    .name("storm_animation_players_reclaimed_total")
                    .help(
                            "AnimationPlayers released back to the pool because their character had"
                                    + " been continuously out of the world longer than"
                                    + " -Dstorm.animplayer.sweep.graceMs.")
                    .labelNames("holder")
                    .register(StormPrometheus.registry());

    private static final Gauge RECENTLY_REMOVED =
            Gauge.builder()
                    .name("storm_recently_removed_players")
                    .help(
                            "Size of IsoPlayer.RecentlyRemoved. Vanilla drains this only when"
                                    + " !GameServer.server, so on a dedicated server it grows without"
                                    + " bound.")
                    .register(StormPrometheus.registry());

    private static final Counter RECENTLY_REMOVED_DRAINED =
            Counter.builder()
                    .name("storm_recently_removed_players_drained_total")
                    .help("Entries dropped from IsoPlayer.RecentlyRemoved by the Storm sweep.")
                    .register(StormPrometheus.registry());

    private StormAnimationPlayerSweepMetrics() {}

    public static void setInUse(int inUse) {
        IN_USE.set(inUse);
    }

    public static void setStranded(int stranded) {
        STRANDED.set(stranded);
    }

    public static void setOutOfWorld(int outOfWorld) {
        OUT_OF_WORLD.set(outOfWorld);
    }

    public static void recordReclaimed(String holder, int count) {
        RECLAIMED.labelValues(holder).inc(count);
    }

    public static void setRecentlyRemoved(int size) {
        RECENTLY_REMOVED.set(size);
    }

    public static void recordRecentlyRemovedDrained(int count) {
        RECENTLY_REMOVED_DRAINED.inc(count);
    }
}
