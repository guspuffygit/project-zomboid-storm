package io.pzstorm.storm.advice.client.chunksovertcp;

import io.pzstorm.storm.client.StormChunksOverTcp;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.core.network.ByteBufferWriter;
import zombie.iso.WorldStreamer;

/**
 * Woven into {@code RequestZipListPacket.write}. When the requests are staged for TCP transfer,
 * writes an empty request list (count 0 — harmless to the server, which queues and immediately
 * recycles an empty ccr) and skips the vanilla body, keeping the rest of {@code
 * WorldStreamer.updateMain} — including the {@code sentRequests} publish the receive path depends
 * on — untouched.
 */
public class RequestZipListWriteAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.Argument(0) ByteBufferWriter writer,
            @Advice.FieldValue("requests") ArrayList<WorldStreamer.ChunkRequest> requests) {
        if (!StormChunksOverTcp.stageForTcp(requests)) {
            return false;
        }
        writer.putInt(0);
        return true;
    }
}
