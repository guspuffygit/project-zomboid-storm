package io.pzstorm.storm.debugging;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.areas.isoregion.IsoRegions;

/**
 * Client-side backend for the {@code /checkroom} and {@code /clearcheckroom} chat commands
 * installed by {@code lua/client/storm/StormCheckRoomCommand.lua}.
 *
 * <p>Room classification (IsoWorldRegion.isFogMask → cutaway) depends on the DataChunk-cached wall
 * bits agreeing with the live IsoGridSquare state. When a wall placed by a player never propagates
 * into the DataChunk (or a wall bit fails to clear on removal), the flood-fill leaks across the
 * missing bit into a neighboring chunk region, inflates the world region past the enclosed /
 * 50%-roofed gates, and the room silently loses its "room" status — cutaway stops hiding walls in
 * front of the player.
 *
 * <p>{@code check(radius)} reconstructs the expected flags from IsoRegions#calculateSquareFlags
 * (private static — reflection) and diffs against IsoRegions#getSquareFlags to find cells where a
 * wall bit is present in reality but missing in the cache. Each such cell's IsoObjects are painted
 * red via {@link IsoObject#setHighlighted(boolean, boolean)} with renderOnce=false so the paint
 * survives across frames. {@code clear(radius)} undoes the paint.
 *
 * <p>Exposed to the client Lua VM by {@code StormEventHandler#onZomboidGlobalsLoad}.
 */
public final class StormIsoRegionCheck {

    private static final int DEFAULT_RADIUS = 32;
    private static final int MAX_RADIUS = 128;
    private static final int MAX_COORDS_IN_SUMMARY = 8;

    private static Method calcSquareFlagsMethod;

    private StormIsoRegionCheck() {}

    @LuaMethod(name = "check")
    public static String check(Integer radiusBoxed) {
        int radius = clampRadius(radiusBoxed);
        IsoPlayer p = IsoPlayer.getInstance();
        if (p == null) {
            return "checkroom: no local player";
        }
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) {
            return "checkroom: cell not loaded";
        }
        Method calc = calcSquareFlagsMethod();
        if (calc == null) {
            return "checkroom: reflection into IsoRegions.calculateSquareFlags failed";
        }

        int px = (int) p.getX(), py = (int) p.getY(), pz = (int) p.getZ();
        int scanned = 0, leaks = 0, painted = 0;
        List<String> leakCoords = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int x = px + dx, y = py + dy;
                IsoGridSquare sq = cell.getGridSquare(x, y, pz);
                if (sq == null) continue;
                scanned++;
                byte stored = IsoRegions.getSquareFlags(x, y, pz);
                byte expected;
                try {
                    expected = (Byte) calc.invoke(null, sq);
                } catch (ReflectiveOperationException e) {
                    return "checkroom: calculateSquareFlags threw: " + e.getMessage();
                }
                boolean leakN =
                        ((expected & IsoRegions.BIT_WALL_N) != 0)
                                && ((stored & IsoRegions.BIT_WALL_N) == 0);
                boolean leakW =
                        ((expected & IsoRegions.BIT_WALL_W) != 0)
                                && ((stored & IsoRegions.BIT_WALL_W) == 0);
                if (!leakN && !leakW) continue;
                leaks++;
                if (leakCoords.size() < MAX_COORDS_IN_SUMMARY) {
                    leakCoords.add(
                            x + "," + y + (leakN && leakW ? " (N+W)" : leakN ? " (N)" : " (W)"));
                }
                for (int i = 0; i < sq.getObjects().size(); i++) {
                    IsoObject o = sq.getObjects().get(i);
                    o.setHighlighted(true, false);
                    o.setHighlightColor(1.0f, 0.0f, 0.0f, 1.0f);
                    painted++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("checkroom @ (")
                .append(px)
                .append(",")
                .append(py)
                .append(",")
                .append(pz)
                .append(")")
                .append(" r=")
                .append(radius)
                .append(" scanned=")
                .append(scanned)
                .append(" leaks=")
                .append(leaks)
                .append(" painted=")
                .append(painted);
        if (!leakCoords.isEmpty()) {
            sb.append(" | ");
            for (int i = 0; i < leakCoords.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(leakCoords.get(i));
            }
            if (leaks > leakCoords.size()) {
                sb.append(", +").append(leaks - leakCoords.size()).append(" more");
            }
        }
        return sb.toString();
    }

    @LuaMethod(name = "clear")
    public static String clear(Integer radiusBoxed) {
        int radius = clampRadius(radiusBoxed);
        IsoPlayer p = IsoPlayer.getInstance();
        if (p == null) {
            return "clearcheckroom: no local player";
        }
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) {
            return "clearcheckroom: cell not loaded";
        }

        int px = (int) p.getX(), py = (int) p.getY(), pz = (int) p.getZ();
        int cleared = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                IsoGridSquare sq = cell.getGridSquare(px + dx, py + dy, pz);
                if (sq == null) continue;
                for (int i = 0; i < sq.getObjects().size(); i++) {
                    IsoObject o = sq.getObjects().get(i);
                    if (o.isHighlighted()) {
                        o.setHighlighted(false, false);
                        cleared++;
                    }
                }
            }
        }
        return "clearcheckroom r=" + radius + " cleared=" + cleared;
    }

    private static int clampRadius(Integer boxed) {
        int r = boxed == null ? DEFAULT_RADIUS : boxed;
        if (r < 1) return 1;
        if (r > MAX_RADIUS) return MAX_RADIUS;
        return r;
    }

    private static Method calcSquareFlagsMethod() {
        Method m = calcSquareFlagsMethod;
        if (m != null) return m;
        try {
            m = IsoRegions.class.getDeclaredMethod("calculateSquareFlags", IsoGridSquare.class);
            m.setAccessible(true);
            calcSquareFlagsMethod = m;
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
