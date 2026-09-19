package io.pzstorm.storm.bullet.trace.scenario;

import io.pzstorm.storm.bullet.trace.BulletBackend;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Mirror of the game's {@code Bullet.cmdBuf} protocol: one shared 4096-byte direct little-endian
 * buffer, {@code clear()}ed (never zeroed) before each command and handed to {@code ToBullet}
 * without a flip, so position is at the end of the command, limit is 4096 and stale bytes of older
 * commands follow the terminator — exactly what the native parser sees in the game.
 */
public final class CmdBuf {

    public static final byte TO_UPDATE_CHUNK = 8;
    public static final byte TO_UPDATE_PLAYER_LIST = 12;
    public static final byte TO_END = -1;

    private final ByteBuffer buf = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);
    private final BulletBackend bullet;

    public CmdBuf(BulletBackend bullet) {
        this.bullet = bullet;
    }

    public ByteBuffer buffer() {
        return buf;
    }

    /** {@code Bullet.updatePlayerList}: (onlineId, floor x, floor y) per player. */
    public void updatePlayerList(int[] onlineIds, float[] xs, float[] ys) {
        buf.clear();
        buf.put(TO_UPDATE_PLAYER_LIST);
        buf.putShort((short) onlineIds.length);
        for (int i = 0; i < onlineIds.length; i++) {
            buf.putInt(onlineIds[i]);
            buf.putInt((int) Math.floor(xs[i]));
            buf.putInt((int) Math.floor(ys[i]));
        }
        buf.put(TO_END);
        buf.put(TO_END);
        catchToBullet();
    }

    /** {@code Bullet.beginUpdateChunk}. */
    public void beginUpdateChunk(int wx, int wy, int minLevel, int maxLevel, int level) {
        buf.clear();
        buf.put(TO_UPDATE_CHUNK);
        buf.putShort((short) wx);
        buf.putShort((short) wy);
        buf.putShort((short) minLevel);
        buf.putShort((short) maxLevel);
        buf.putShort((short) level);
    }

    /** {@code Bullet.updateChunk}: square (x, y) of the chunk has {@code n} shape bytes. */
    public void updateChunk(int x, int y, int n, byte[] shapes) {
        buf.put((byte) x);
        buf.put((byte) y);
        buf.put((byte) n);
        for (int i = 0; i < n; i++) {
            buf.put(shapes[i]);
        }
    }

    /** {@code Bullet.endUpdateChunk}: sends unless nothing but the header was written. */
    public void endUpdateChunk() {
        if (buf.position() != 11) {
            buf.put(TO_END);
            buf.put(TO_END);
            catchToBullet();
        }
    }

    /** {@code Bullet.CatchToBullet}: RuntimeExceptions are logged and swallowed by the game. */
    private void catchToBullet() {
        try {
            bullet.ToBullet(buf);
        } catch (RuntimeException ignored) {
            // the game logs and continues
        }
    }
}
