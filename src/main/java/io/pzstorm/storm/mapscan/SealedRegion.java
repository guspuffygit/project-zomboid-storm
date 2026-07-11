package io.pzstorm.storm.mapscan;

import java.util.ArrayList;

/** A globally-confirmed sealed area: walkable squares with no path to ground level anywhere. */
public final class SealedRegion {

    /** Member squares as {@link SquareCoord}-packed world coordinates. */
    public final ArrayList<Long> squares = new ArrayList<>();

    public int minX = Integer.MAX_VALUE;
    public int minY = Integer.MAX_VALUE;
    public int minZ = Integer.MAX_VALUE;
    public int maxX = Integer.MIN_VALUE;
    public int maxY = Integer.MIN_VALUE;
    public int maxZ = Integer.MIN_VALUE;

    void add(long packedSquare) {
        squares.add(packedSquare);
        int x = SquareCoord.unpackX(packedSquare);
        int y = SquareCoord.unpackY(packedSquare);
        int z = SquareCoord.unpackZ(packedSquare);
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    public int squareCount() {
        return squares.size();
    }
}
