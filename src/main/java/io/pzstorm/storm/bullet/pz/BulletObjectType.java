// Port of PZ glue enum BulletObjectType (values from BulletObject::BulletObject @0015d300 name
// table and the WorldSimulation add* functions).
package io.pzstorm.storm.bullet.pz;

public final class BulletObjectType {

    private BulletObjectType() {}

    public static final int Unknown = 0;
    public static final int Vehicle = 1;
    public static final int VehiclePart = 2;
    public static final int WallN = 3;
    public static final int WallW = 4;
    public static final int WallS = 5;
    public static final int WallE = 6;
    public static final int Solid = 7;
    public static final int Floor = 8;
    public static final int Tree = 9;
    public static final int Mesh = 10;
    public static final int Ragdoll = 11;
    public static final int RagdollPart = 12;
    public static final int Stairs = 13;
}
