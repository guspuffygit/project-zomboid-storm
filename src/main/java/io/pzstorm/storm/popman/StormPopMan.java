package io.pzstorm.storm.popman;

import java.nio.ByteBuffer;

/**
 * Java replacement for the {@code PZPopMan64} JNI surface of {@code
 * zombie.popman.ZombiePopulationManager}. Every method here mirrors one {@code n_*} native
 * one-for-one in name and signature so {@link
 * io.pzstorm.storm.patch.popman.ZombiePopulationManagerNativePatch} can bind them positionally.
 * Parameter names follow the contract recovered in {@code docs/re-popman/01-java-contract.md}.
 *
 * <p>Together with {@link StormMapCollisionData}, {@link StormMPDebugInfo}, {@link
 * StormDebugCommands} and {@link StormZombiePopulationRenderer} this covers all 59 exports of the
 * DLL, and the patch removes the {@code System.loadLibrary} from {@code init()}, so the library is
 * never loaded.
 */
public final class StormPopMan {

    private static final PopManCore CORE = new PopManCore();

    private static volatile PopManGameBridge bridge;

    private StormPopMan() {}

    public static PopManCore core() {
        return CORE;
    }

    /** Null on MP clients and before {@code n_init}. */
    public static PopManGameBridge bridge() {
        return bridge;
    }

    /**
     * Binds the simulation to the collision map {@code MapCollisionData.init} already set up. A
     * client gets no bridge at all, as natively: {@link PopManCore#init} returns before building
     * anything when {@code isClient} is set.
     */
    public static void n_init(
            boolean isClient, boolean isServer, int minX, int minY, int width, int height) {
        PopManGameBridge bound =
                isClient
                        ? null
                        : new PopManGameBridge(
                                CORE,
                                StormMapCollisionData.collision(),
                                StormMapCollisionData.gameState());
        bridge = bound;
        CORE.setEnvironment(bound);
        CORE.init(isClient, isServer, minX, minY, width, height);
    }

    /**
     * The whole-config setter: nine values in the order the native stored them, each landing on the
     * same key {@code n_configFloat} / {@code n_configInt} would use. Vanilla never calls it —
     * {@code onConfigReloaded} uses the per-key setters — but it is bound all the same.
     */
    public static void n_config(
            float populationMultiplier,
            float populationStartMultiplier,
            float populationPeakMultiplier,
            int populationPeakDay,
            float respawnHours,
            float respawnUnseenHours,
            float respawnMultiplier,
            float redistributeHours,
            int followSoundDistance) {
        CORE.configFloat("PopulationMultiplier", populationMultiplier);
        CORE.configFloat("PopulationStartMultiplier", populationStartMultiplier);
        CORE.configFloat("PopulationPeakMultiplier", populationPeakMultiplier);
        CORE.configInt("PopulationPeakDay", populationPeakDay);
        CORE.configFloat("RespawnHours", respawnHours);
        CORE.configFloat("RespawnUnseenHours", respawnUnseenHours);
        CORE.configFloat("RespawnMultiplier", respawnMultiplier);
        CORE.configFloat("RedistributeHours", redistributeHours);
        CORE.configInt("FollowSoundDistance", followSoundDistance);
    }

    public static void n_configFloat(String key, float value) {
        CORE.configFloat(key, value);
    }

    public static void n_configInt(String key, int value) {
        CORE.configInt(key, value);
    }

    public static void n_setSpawnOrigins(int[] xywh) {
        CORE.setSpawnOrigins(xywh);
    }

    public static void n_setOutfitNames(String[] lowercased) {
        CORE.setOutfitNames(lowercased);
    }

    public static void n_updateMain(float timeMultiplier, double worldAgeHours) {
        CORE.updateMain(timeMultiplier, worldAgeHours);
    }

    public static boolean n_hasDataForThread() {
        return CORE.hasDataForThread();
    }

    public static boolean n_readyToPause() {
        return CORE.readyToPause();
    }

    public static void n_updateThread() {
        CORE.updateThread();
    }

    public static boolean n_shouldWait() {
        return CORE.shouldWait();
    }

    public static void n_beginSaveRealZombies(int totalCount) {
        CORE.beginSaveRealZombies(totalCount);
    }

    public static void n_saveRealZombies(int count, ByteBuffer buf) {
        CORE.saveRealZombies(count, buf);
    }

    public static void n_save() {
        CORE.save();
    }

    public static void n_saveCell(int cellX, int cellY) {
        CORE.saveCell(cellX, cellY);
    }

    public static void n_stop() {
        CORE.stop();
    }

    public static void n_addZombie(
            float x,
            float y,
            float z,
            byte dir,
            int descriptorID,
            int stateFlags,
            int pathTargetX,
            int pathTargetY) {
        CORE.addZombie(x, y, z, dir, descriptorID, stateFlags, pathTargetX, pathTargetY);
    }

    public static void n_aggroTarget(int id, int x, int y) {
        CORE.aggroTarget(id, x, y);
    }

    public static void n_loadChunk(int wx, int wy, boolean loaded) {
        CORE.loadChunk(wx, wy, loaded);
    }

    public static void n_loadedAreas(int count, int[] areas, boolean isServerCells) {
        CORE.loadedAreas(count, areas, isServerCells);
    }

    public static void n_realZombieCount(short count, short[] triples) {
        CORE.realZombieCount(count, triples);
    }

    public static void n_spawnHorde(
            int spawnX,
            int spawnY,
            int spawnW,
            int spawnH,
            float targetX,
            float targetY,
            int count) {
        CORE.spawnHorde(spawnX, spawnY, spawnW, spawnH, targetX, targetY, count);
    }

    public static void n_worldSound(int x, int y, int radius, int volume) {
        CORE.worldSound(x, y, radius, volume);
    }

    public static int n_getAddZombieCount() {
        return CORE.getAddZombieCount();
    }

    public static int n_getAddZombieData(int offset, ByteBuffer buf) {
        return CORE.getAddZombieData(offset, buf);
    }

    public static boolean n_hasRadarData() {
        return CORE.hasRadarData();
    }

    public static void n_requestRadarData() {
        CORE.requestRadarData();
    }

    public static int n_getRadarZombieData(float[] xy) {
        return CORE.getRadarZombieData(xy);
    }
}
