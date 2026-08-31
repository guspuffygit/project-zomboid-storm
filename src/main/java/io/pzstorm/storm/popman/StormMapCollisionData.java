package io.pzstorm.storm.popman;

import io.pzstorm.storm.logging.StormLogger;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Java replacement for the 17 {@code PZPopMan64} natives of {@code zombie.MapCollisionData}: the
 * collision map, its {@code chunkdata} persistence, the wall-following pathfinder and the game
 * state the DLL read through {@code n_setGameState}. Every method mirrors one native one-for-one so
 * {@link io.pzstorm.storm.patch.popman.MapCollisionDataNativePatch} can bind them by name.
 *
 * <p>The state here is the DLL's global state: one collision map, one metagrid registry and one
 * {@link PopManGameState} that {@link StormPopMan}'s population shares.
 */
public final class StormMapCollisionData implements PopManChunkDataSource {

    private static final PopManGameState STATE = new PopManGameState();
    private static final PopManMetaRegistry REGISTRY = new PopManMetaRegistry();
    private static final PopManCollisionGrid GRID = new PopManCollisionGrid();
    private static final PopManMapCollision COLLISION = new PopManMapCollision(GRID);
    private static final StormMapCollisionData FILES = new StormMapCollisionData();

    private StormMapCollisionData() {}

    public static PopManGameState gameState() {
        return STATE;
    }

    public static PopManMetaRegistry registry() {
        return REGISTRY;
    }

    public static PopManCollisionGrid grid() {
        return GRID;
    }

    public static PopManMapCollision collision() {
        return COLLISION;
    }

    // --- natives ------------------------------------------------------------

    public static void n_init(int minX, int minY, int width, int height) {
        GRID.init(FILES, REGISTRY, () -> STATE.noSave, minX, minY, width, height);
    }

    public static void n_chunkUpdateTask(int wx, int wy, byte[] data) {
        COLLISION.chunkUpdateTask(wx, wy, data);
    }

    public static void n_squareUpdateTask(int count, ByteBuffer buffer) {
        COLLISION.squareUpdateTask(count, buffer);
    }

    public static int n_pathTask(int startX, int startY, int endX, int endY, int[] curXY) {
        return COLLISION.pathTask(startX, startY, endX, endY, curXY);
    }

    public static boolean n_hasDataForThread() {
        return COLLISION.hasDataForThread();
    }

    public static boolean n_shouldWait() {
        return COLLISION.shouldWait();
    }

    public static void n_update() {
        COLLISION.update();
    }

    public static void n_save() {
        COLLISION.save();
    }

    public static void n_stop() {
        COLLISION.stop();
    }

    public static void n_setGameState(String key, boolean value) {
        STATE.setBoolean(key, value);
    }

    public static void n_setGameState(String key, double value) {
        STATE.setDouble(key, value);
    }

    public static void n_setGameState(String key, float value) {
        STATE.setFloat(key, value);
    }

    public static void n_setGameState(String key, int value) {
        STATE.setInt(key, value);
    }

    public static void n_setGameState(String key, String value) {
        STATE.setString(key, value);
    }

    public static void n_initMetaGrid(int minX, int minY, int width, int height) {
        REGISTRY.initGrid(minX, minY, width, height);
    }

    public static void n_initMetaCell(int cellX, int cellY, String path) {
        REGISTRY.initCell(cellX, cellY, path);
    }

    public static void n_initMetaChunk(int cellX, int cellY, int wx, int wy, int intensity) {
        REGISTRY.initChunk(cellX, cellY, wx, wy, intensity);
    }

    // --- files --------------------------------------------------------------

    /**
     * {@code GameModeCacheDir + GameSaveWorld + / + chunkdata + / + chunkdata_cx_cy.bin}, the
     * string the DLL built; the Java side hands {@code GameModeCacheDir} over with its trailing
     * separator already attached.
     */
    static Path savedPath(PopManGameState state, int cellX, int cellY) {
        return Paths.get(
                state.gameModeCacheDir
                        + state.gameSaveWorld
                        + File.separator
                        + state.subdirChunkData
                        + File.separator
                        + "chunkdata_"
                        + cellX
                        + "_"
                        + cellY
                        + ".bin");
    }

    @Override
    public byte[] readSaved(int cellX, int cellY) {
        return read(savedPath(STATE, cellX, cellY));
    }

    @Override
    public byte[] readShipped(String path) {
        return read(Paths.get(path));
    }

    /**
     * The native opened the file with no directory creation; Storm creates the {@code chunkdata}
     * directory so a first save into a fresh world does not silently lose every dirty cell.
     */
    @Override
    public void writeSaved(int cellX, int cellY, byte[] data) throws IOException {
        Path file = savedPath(STATE, cellX, cellY);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, data);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            StormLogger.LOGGER.error("MapCollisionData: unable to read " + file, e);
            return null;
        }
    }
}
