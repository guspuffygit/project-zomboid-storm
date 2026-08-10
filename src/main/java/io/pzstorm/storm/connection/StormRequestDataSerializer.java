package io.pzstorm.storm.connection;

import java.nio.ByteBuffer;
import zombie.PersistentOutfits;
import zombie.SharedDescriptors;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.packets.RequestDataPacket;
import zombie.radio.ZomboidRadio;
import zombie.worldMap.network.WorldMapServer;

/**
 * Serializes the client-pulled {@link RequestDataPacket.RequestID} payloads exactly as vanilla
 * {@code RequestDataPacket.doProcessRequest} does, but returns the bytes instead of handing them to
 * the UDP part-transfer machinery. The game-port TCP endpoint serves these to Storm clients.
 *
 * <p>MUST run on the server main thread ({@link io.pzstorm.storm.util.StormServerTaskQueue}): the
 * sources (PersistentOutfits, SharedDescriptors, RecordedMedia, WorldMapServer) are mutated by
 * main-thread packet handlers, and this reuses vanilla's shared {@code largeFileBb} staging buffer,
 * which is only safe because {@code RequestDataManager.RequestData} copies out of it within the
 * same main-thread call — no reference survives across ticks.
 */
public final class StormRequestDataSerializer {

    private StormRequestDataSerializer() {}

    /** Serialize one payload. Main thread only. */
    public static byte[] serialize(RequestDataPacket.RequestID id) throws Exception {
        new RequestDataPacket().allocateLargeFileBB();
        switch (id) {
            case ZombieOutfitDescriptors -> {
                RequestDataPacket.largeFileBb.clear();
                PersistentOutfits.instance.save(RequestDataPacket.largeFileBb);
            }
            case PlayerZombieDescriptors -> serializePlayerZombieDescriptors();
            case RadioData -> {
                RequestDataPacket.largeFileBb.clear();
                ZomboidRadio.getInstance()
                        .getRecordedMedia()
                        .sendRequestData(RequestDataPacket.largeFileBb);
            }
            case WorldMap -> {
                RequestDataPacket.largeFileBb.clear();
                WorldMapServer.instance.sendRequestData(RequestDataPacket.largeFileBb);
            }
            default -> throw new IllegalArgumentException("unsupported request id: " + id);
        }
        ByteBuffer bb = RequestDataPacket.largeFileBb;
        byte[] out = new byte[bb.position()];
        System.arraycopy(bb.array(), 0, out, 0, out.length);
        return out;
    }

    private static void serializePlayerZombieDescriptors() throws Exception {
        SharedDescriptors.Descriptor[] descs = SharedDescriptors.getPlayerZombieDescriptors();
        int count = 0;
        for (SharedDescriptors.Descriptor desc : descs) {
            if (desc != null) {
                count++;
            }
        }
        // Vanilla grows the shared buffer in place for this id; mirror it so a later vanilla
        // UDP transfer sees the same capacity it would have set up itself.
        if (count * 2 * 1024 > RequestDataPacket.largeFileBb.capacity()) {
            RequestDataPacket.largeFileBb = ByteBuffer.allocate(count * 2 * 1024);
            RequestDataPacket.largeFileBbReader =
                    new ByteBufferReader(RequestDataPacket.largeFileBb);
            RequestDataPacket.largeFileBbWriter =
                    new ByteBufferWriter(RequestDataPacket.largeFileBb);
        }
        RequestDataPacket.largeFileBb.clear();
        RequestDataPacket.largeFileBb.putShort((short) count);
        for (SharedDescriptors.Descriptor desc : descs) {
            if (desc != null) {
                desc.save(RequestDataPacket.largeFileBbWriter);
            }
        }
    }
}
