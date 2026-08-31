package io.pzstorm.storm.popman;

import java.io.IOException;

/**
 * The files behind the collision map: the save's own {@code chunkdata} copy of a cell and the
 * shipped map files the metagrid registered for it. Called on the {@code MapCollisionData} thread
 * only.
 */
public interface PopManChunkDataSource {

    /** The save's {@code chunkdata_<cx>_<cy>.bin}, or null when there is none. */
    byte[] readSaved(int cellX, int cellY);

    /** A shipped map file by the path {@code MapCollisionData.initMetaCell} registered. */
    byte[] readShipped(String path);

    void writeSaved(int cellX, int cellY, byte[] data) throws IOException;
}
