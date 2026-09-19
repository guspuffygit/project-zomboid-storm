// Port of PZ glue ServerCell: ctor @00172940, ~ServerCell @00172a10, contains @00172af0,
// getChunk @00172b10.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btGlobals;

/** A dedicated-server cell: 5x5 chunks it owns plus one ground body. */
public class ServerCell {

    /** +0 */
    public int cellX;

    /** +4 */
    public int cellY;

    /** +8 */
    public btRigidBody groundBody;

    /** +0x10 Chunk*[5][5], index x * 5 + y. */
    public final Chunk[] chunks = new Chunk[25];

    public ServerCell(int cellX, int cellY) {
        this.groundBody = null;
        this.cellX = cellX;
        this.cellY = cellY;
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                chunks[x * 5 + y] = new Chunk(this.cellX * 5 + x, this.cellY * 5 + y);
            }
        }
    }

    /** ~ServerCell: ground body leaves the world, chunks are deleted, the body is deleted. */
    public void destroy() {
        ((btDiscreteDynamicsWorld) btGlobals.gDynamicsWorld).removeRigidBody(groundBody);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                Chunk c = chunks[x * 5 + y];
                if (c != null) {
                    c.destroy();
                }
            }
        }
    }

    public boolean contains(int wx, int wy) {
        return Integer.compareUnsigned(wx - cellX * 5, 5) < 0
                && Integer.compareUnsigned(wy - cellY * 5, 5) < 0;
    }

    public Chunk getChunk(int wx, int wy) {
        if (contains(wx, wy)) {
            return chunks[(wx - cellX * 5) * 5 + (wy - cellY * 5)];
        }
        return null;
    }
}
