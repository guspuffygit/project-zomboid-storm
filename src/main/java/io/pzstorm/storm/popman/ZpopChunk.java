package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/** One chunk's worth of a {@code zpop_<cellX>_<cellY>.bin} cell file. */
public final class ZpopChunk {

    public static final int CHUNKS_PER_CELL_SIDE = 32;
    public static final int CHUNKS_PER_CELL = CHUNKS_PER_CELL_SIDE * CHUNKS_PER_CELL_SIDE;

    public final List<PopManZombie> zombies = new ArrayList<>();

    /**
     * The chunk's stochastically-rounded density target — how many zombies the simulation wants
     * living here before the day multipliers are applied. Native {@code chunk+0x04}. Zero in every
     * record of the 651-file corpus, since it is recomputed on load.
     */
    public short basePop;

    /** World-age hours at which a player last had this chunk loaded. Native {@code chunk+0x18}. */
    public float lastSeenTime;

    /** World-age hours at the chunk's last repopulation. Native {@code chunk+0x1c}. */
    public float lastRepopTime;
}
