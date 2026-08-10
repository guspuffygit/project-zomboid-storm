package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Method;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import zombie.PersistentOutfits;
import zombie.core.Translator;
import zombie.core.network.ByteBufferReader;
import zombie.gameStates.GameLoadingState;
import zombie.network.GameClient;
import zombie.network.packets.RequestDataPacket;
import zombie.radio.media.RecordedMedia;
import zombie.worldMap.network.WorldMapClient;

/**
 * Client half of the loading-phase RequestData migration to TCP. Called from the loader thread at
 * the top of {@code GameClient.GameLoadingRequestData} (via {@code GameLoadingRequestDataAdvice});
 * when it returns {@code true} the vanilla UDP request chain is skipped entirely.
 *
 * <p>All four payloads are downloaded first and only then applied, so a mid-download failure
 * returns {@code false} with no state touched and the vanilla UDP chain runs cleanly from the
 * start. Per-payload apply failures are logged and skipped — the same per-id catch-and-continue
 * behavior as vanilla {@code RequestDataPacket.doProcessData}.
 *
 * <p>Applying on the loader thread (vanilla parses on the packet thread while the loader spins) is
 * safe: the loaded state is only read later by this same loader thread.
 */
public final class StormRequestDataOverTcp {

    /** Vanilla request-chain order from {@code RequestDataPacket.sendNextRequest}. */
    private static final RequestDataPacket.RequestID[] CHAIN = {
        RequestDataPacket.RequestID.ZombieOutfitDescriptors,
        RequestDataPacket.RequestID.PlayerZombieDescriptors,
        RequestDataPacket.RequestID.RadioData,
        RequestDataPacket.RequestID.WorldMap,
    };

    private StormRequestDataOverTcp() {}

    /**
     * Fetch and apply all loading-phase payloads over the TCP channel. Returns {@code false}
     * (having applied nothing) if there is no session or any download fails.
     */
    public static boolean tryFetchAll() {
        try {
            if (!StormTcpChannel.isEstablished()) {
                return false;
            }
            Map<RequestDataPacket.RequestID, byte[]> payloads =
                    new EnumMap<>(RequestDataPacket.RequestID.class);
            long started = System.currentTimeMillis();
            for (RequestDataPacket.RequestID id : CHAIN) {
                byte[] payload = fetch(id);
                if (payload == null) {
                    return false;
                }
                payloads.put(id, payload);
            }
            long downloadMillis = System.currentTimeMillis() - started;
            for (RequestDataPacket.RequestID id : CHAIN) {
                apply(id, payloads.get(id));
            }
            GameClient.instance.setRequest(GameClient.RequestState.Complete);
            LOGGER.info(
                    "Loading-phase request data received over TCP in {}ms ({} bytes total)",
                    downloadMillis,
                    payloads.values().stream().mapToLong(p -> p.length).sum());
            return true;
        } catch (Throwable t) {
            LOGGER.error("Request data over TCP failed; falling back to UDP", t);
            return false;
        }
    }

    @org.jetbrains.annotations.Nullable
    private static byte[] fetch(RequestDataPacket.RequestID id) {
        HttpRequest.Builder builder =
                StormTcpChannel.authenticatedRequest("/storm/game/request-data?id=" + id.name());
        if (builder == null) {
            return null;
        }
        setLoadingString(id, 0);
        try {
            HttpResponse<byte[]> response = StormTcpChannel.send(builder.GET().build());
            if (response.statusCode() != 200) {
                LOGGER.info(
                        "TCP request-data {} returned {}; falling back to UDP",
                        id,
                        response.statusCode());
                return null;
            }
            setLoadingString(id, 100);
            return response.body();
        } catch (Exception e) {
            LOGGER.info("TCP request-data {} failed ({}); falling back to UDP", id, e.toString());
            return null;
        }
    }

    private static void apply(RequestDataPacket.RequestID id, byte[] payload) {
        try {
            ByteBufferReader reader = new ByteBufferReader(ByteBuffer.wrap(payload));
            switch (id) {
                case ZombieOutfitDescriptors -> PersistentOutfits.instance.load(reader.bb);
                case PlayerZombieDescriptors -> receivePlayerZombieDescriptors(reader);
                case RadioData -> RecordedMedia.receiveRequestData(reader);
                case WorldMap -> WorldMapClient.instance.receiveRequestData(reader);
                default -> throw new IllegalArgumentException("unexpected id " + id);
            }
        } catch (Throwable t) {
            // Same contract as vanilla doProcessData: a bad payload for one id is logged and
            // the rest still load.
            LOGGER.error("Failed to apply TCP request data {}", id, t);
        }
    }

    /**
     * Vanilla's descriptor parse (with its inlined world-version constant) is a private method on
     * the packet; reflection keeps that constant in vanilla code so a PZ bump can't desync a copy.
     */
    private static void receivePlayerZombieDescriptors(ByteBufferReader reader) throws Exception {
        Method receive =
                RequestDataPacket.class.getDeclaredMethod(
                        "receivePlayerZombieDescriptors", ByteBufferReader.class);
        receive.setAccessible(true);
        receive.invoke(new RequestDataPacket(), reader);
    }

    private static void setLoadingString(RequestDataPacket.RequestID id, int percent) {
        try {
            GameLoadingState.gameLoadingString =
                    Translator.getText("IGUI_MP_DownloadedLargeFile", percent, id.getDescriptor());
        } catch (Throwable ignored) {
            // progress text only
        }
    }
}
