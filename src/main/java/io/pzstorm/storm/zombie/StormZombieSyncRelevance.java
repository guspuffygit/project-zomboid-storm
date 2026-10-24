package io.pzstorm.storm.zombie;

import io.pzstorm.storm.logging.StormLogger;
import zombie.characters.IsoZombie;
import zombie.core.raknet.UdpConnection;

/**
 * Stands in for the two {@code IsoZombie.getOwner()} calls in {@code
 * NetworkZombiePacker.getZombieData(UdpConnection, ZombieSynchronizationPacket)}, see {@code
 * ZombieSyncRelevanceFirstPatch}.
 *
 * <p>{@code NetworkZombiePacker.send} runs {@code getZombieData} once per connection per tick, and
 * its second loop tests every zombie processed this tick against that connection:
 *
 * <pre>{@code
 * z.getOwner() != null && z.getOwner() != connection
 *     && connection.RelevantTo(z.getX(), z.getY(), (connection.getRelevantRange() - 2) * 10)
 *     && z.onlineId != -1
 * }</pre>
 *
 * Both {@code getOwner()} calls are an ECS component-map probe, made for every (connection, zombie)
 * pair before the cheap position test, so the probes grow with players times zombies. On a live
 * 42.21 server at 133-147 players and about 8,800 zombies, {@code getOwner} under {@code
 * getZombieData} was 2.96% of the main thread; {@code getZombieData} as a whole was 4.66%.
 *
 * <p>{@link #ownerIfRelevant} runs the position test first and returns {@code null} for a zombie
 * that is not relevant to the connection being packed, without probing the owner. Outcome identity,
 * with R the position test and O the real owner:
 *
 * <ul>
 *   <li>R false: the substitute yields {@code null}, so the first clause is false and the condition
 *       is false. Vanilla's third clause is R, so vanilla's condition is false too.
 *   <li>R true: both substitutes yield O, and the condition is evaluated exactly as vanilla's.
 * </ul>
 *
 * Nothing the condition reads changes between its clauses (the loop runs on the main thread and
 * none of the four reads has a side effect), so evaluating R before or after the owner probes gives
 * the same R. The position test is vanilla's own expression on vanilla's own arguments.
 *
 * <p>The connection is the one {@code getZombieData} was called with, recorded by the enter advice
 * and cleared on exit (including a throwing exit). With no connection recorded, or after any
 * exception in this class, the substitute is plain {@code getOwner()}. Main thread only, like the
 * rest of {@code NetworkZombiePacker.postupdate}.
 */
public final class StormZombieSyncRelevance {

    /** The connection the current {@code getZombieData} call is packing for, or {@code null}. */
    private static UdpConnection packing;

    private static boolean disabled;

    private StormZombieSyncRelevance() {}

    public static void begin(UdpConnection connection) {
        packing = connection;
    }

    public static void end() {
        packing = null;
    }

    public static UdpConnection ownerIfRelevant(IsoZombie zombie) {
        UdpConnection connection = packing;
        if (connection != null && !disabled) {
            try {
                if (!connection.RelevantTo(
                        zombie.getX(), zombie.getY(), (connection.getRelevantRange() - 2) * 10)) {
                    return null;
                }
            } catch (Throwable t) {
                disable(t);
            }
        }
        return zombie.getOwner();
    }

    private static void disable(Throwable t) {
        disabled = true;
        StormLogger.LOGGER.error(
                "StormZombieSyncRelevance failed; NetworkZombiePacker.getZombieData runs vanilla"
                        + " for this session",
                t);
    }

    static boolean isDisabled() {
        return disabled;
    }

    /** Test hook: forget the recorded connection and re-enable after a failure. */
    static void reset() {
        packing = null;
        disabled = false;
    }
}
