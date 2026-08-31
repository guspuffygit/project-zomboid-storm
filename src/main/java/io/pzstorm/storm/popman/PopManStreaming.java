package io.pzstorm.storm.popman;

/**
 * The handover between the virtual population and the real game world.
 *
 * <p>A chunk streaming in is the moment its virtual zombies stop being bookkeeping and become
 * actual entities: they leave the chunk's resident list, move from the cell's virtual tally to its
 * real one, and are queued for the main thread to spawn. Streaming out is only a flag — the game
 * hands the survivors back through a separate path.
 */
public final class PopManStreaming {

    private PopManStreaming() {}

    public static void applyChunkLoad(
            PopManCellMap cells,
            int chunkX,
            int chunkY,
            boolean loaded,
            long nowMs,
            PopManResultFrame out) {

        PopManCell cell = cells.residentForChunk(chunkX, chunkY);
        if (cell == null) {
            return;
        }
        cell.setChunkStreamedIn(chunkX, chunkY, loaded);
        if (!loaded) {
            return;
        }
        cell.lastTouchedMs = nowMs;

        PopManChunk chunk = cell.chunkAt(chunkX, chunkY);
        if (chunk == null || chunk.zombies.isEmpty()) {
            return;
        }
        int realised = chunk.zombies.size();
        cell.virtualCount -= (short) realised;
        cell.realCount += (short) realised;
        out.spawns.addAll(chunk.zombies);
        chunk.zombies.clear();
        cell.dirty = true;
    }
}
