package io.pzstorm.storm.popman;

/**
 * The two things the population cannot work out for itself: what the map looks like, and how many
 * zombies the map wants.
 *
 * <p>The native read both from {@code MapCollisionData}, a separate subsystem living in the same
 * DLL that is not part of this port. Everything else the population used to ask — passability,
 * spawn validity, whether a chunk is worth trying — is derived from these two answers by {@link
 * PopManMap}, exactly as the native derived it.
 *
 * <p>Both methods are called from the population worker thread only.
 */
public interface PopManWorld {

    /**
     * Collision flags for a square: {@link PopManMap#BIT_SOLID} and friends.
     *
     * <p>The native distinguished two kinds of "no answer" and the difference is load-bearing: a
     * square outside the world returns {@link PopManMap#BIT_SOLID} so it can never be spawned on,
     * while a square inside the world whose cell is not loaded returns {@code 0} and therefore
     * looks perfectly walkable.
     */
    int squareFlags(int squareX, int squareY);

    /**
     * The map's 0..255 zombie-density byte for a chunk, or {@link PopManPopulation#NO_DENSITY_DATA}
     * where the map has no data.
     */
    int densityByte(int chunkX, int chunkY);
}
