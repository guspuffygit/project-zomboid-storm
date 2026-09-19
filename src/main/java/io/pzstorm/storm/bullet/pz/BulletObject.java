// Port of PZ glue BulletObject (0x98 bytes), ctor @0015d300.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.TreeMap;

/**
 * User pointer attached to PZ collision objects. The ctor builds a per-object {@code
 * std::map<BulletObjectType, std::string>} of type names from an initializer list; the list keys
 * "VehiclePart" with 1 (same as "Vehicle"), so map insertion drops it and key 2 has no name.
 */
public class BulletObject {

    /** +0 (btRigidBody* or btCollisionObject* for stairs) */
    public btCollisionObject body;

    /** +8 BulletObjectType */
    public int type;

    /** +0x10 */
    public double x;

    /** +0x18 */
    public double y;

    /** +0x20 (level) */
    public double z;

    /** +0x28: owner id (vehicle / ballistics target id), -1. */
    public int id = -1;

    /** +0x30 */
    public PZVehicle vehicle;

    /** +0x38: set by collisionCallback to the colliding vehicle's id, -1. */
    public int collidedVehicleId = -1;

    /** +0x3c: part index (ballistics target), -1. */
    public int partIndex = -1;

    /** +0x40 */
    public boolean flag40;

    /** +0x48 */
    public final btVector3 color = new btVector3();

    /** +0x68 */
    public final TreeMap<Integer, String> names = new TreeMap<>();

    public BulletObject(btCollisionObject body, double x, double y, double z, int type) {
        this.body = body;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color.set(PZbtVector3.White);
        String[] n = {
            "Unknown",
            "Vehicle",
            "VehiclePart",
            "WallN",
            "WallW",
            "WallS",
            "WallE",
            "Solid",
            "Floor",
            "Tree",
            "Mesh",
            "Ragdoll",
            "RagdollPart",
            "Stairs"
        };
        int[] k = {0, 1, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
        for (int i = 0; i < n.length; i++) {
            names.putIfAbsent(k[i], n[i]);
        }
    }
}
