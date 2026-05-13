package io.pzstorm.storm.advice.worldmapallknown;

import java.util.HashMap;
import net.bytebuddy.asm.Advice;
import zombie.SandboxOptions;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.network.IConnection;
import zombie.network.packets.character.PlayerVisitedPacket;
import zombie.worldMap.WorldMapVisited;

/**
 * Advice for {@code WorldMapVisitedServer.loadUser(IConnection)}.
 *
 * <p>Vanilla {@code loadUser} never consults the {@code Map.MapAllKnown} sandbox option, so the
 * per-player {@code byte[]} stored in {@code dictionary} only ever contains the {@code BIT_KNOWN}
 * bits the player has personally walked next to. On a first login the client-side {@code
 * WorldMapVisited.getInstance()} marks every cell known locally and no {@code PlayerVisitedPacket}
 * is sent (no zip file yet), so the map appears revealed. On any subsequent login the server loads
 * the per-player zip and sends it via {@code PlayerVisitedPacket.HandleSendPacket}, whose {@code
 * processClient} does a raw {@code System.arraycopy} into the client's {@code visited[]} &mdash;
 * overwriting the all-known bits the client just set, leaving only the walked tiles.
 *
 * <p>This advice runs on exit and, when {@code MapAllKnown} is enabled, ORs {@code BIT_KNOWN} into
 * the entire entity for this user and (re)sends it. The server-side {@code byte[]} now carries the
 * all-known state, so the next {@code unloadUser} persists it to the zip and every subsequent login
 * restores it.
 */
public class LoadUserMapAllKnownAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) IConnection connection,
            @Advice.FieldValue("dictionary") HashMap<String, byte[]> dictionary) {
        if (!SandboxOptions.getInstance().map.mapAllKnown.getValue()) {
            return;
        }
        if (connection == null) {
            return;
        }
        byte[] entity = dictionary.get(connection.getUserName());
        if (entity == null) {
            return;
        }
        IsoMetaGrid grid = IsoWorld.instance.getMetaGrid();
        WorldMapVisited.setKnownInSquares(
                grid.minX * 256,
                grid.minY * 256,
                (grid.maxX + 1) * 256,
                (grid.maxY + 1) * 256,
                entity);
        PlayerVisitedPacket.HandleSendPacket(entity, connection);
    }
}
