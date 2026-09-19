// Port of PZ glue PZbtVector3 statics (_GLOBAL__sub_I_PZbtVector3.cpp @00148730).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btVector3;

public final class PZbtVector3 {

    private PZbtVector3() {}

    public static final btVector3 White = new btVector3(1.0, 1.0, 1.0);
    public static final btVector3 Red = new btVector3(1.0, 0.0, 0.0);
    public static final btVector3 Blue = new btVector3(0.0, 0.0, 1.0);
    public static final btVector3 Green = new btVector3(0.0, 1.0, 0.0);
    public static final btVector3 Pink = new btVector3(1.0, 0.0, 1.0);
    public static final btVector3 Purple = new btVector3(0.5, 0.0, 1.0);
    public static final btVector3 Yellow = new btVector3(1.0, 1.0, 0.0);
    public static final btVector3 Cyan = new btVector3(0.0, 1.0, 1.0);
    public static final btVector3 Orange = new btVector3(1.0, 0.5, 0.0);
    public static final btVector3 btVector3Zero = new btVector3(0.0, 0.0, 0.0);
    public static final btVector3 btVector3One = new btVector3(1.0, 1.0, 1.0);
    public static final btVector3 btVector3Half = new btVector3(0.5, 0.5, 0.5);
    public static final btVector3 btVector3Up = new btVector3(0.0, 1.0, 0.0);
    public static final btVector3 btVector3Forward = new btVector3(0.0, 0.0, 1.0);
    public static final btVector3 btVector3Right = new btVector3(1.0, 0.0, 0.0);

    /** 1e30 (bits 0x46293e5939a08cea). */
    public static final btVector3 btVector3MaxPositive =
            new btVector3(
                    Double.longBitsToDouble(0x46293e5939a08ceaL),
                    Double.longBitsToDouble(0x46293e5939a08ceaL),
                    Double.longBitsToDouble(0x46293e5939a08ceaL));

    public static final btVector3 btVector3MaxNegative =
            new btVector3(
                    -Double.longBitsToDouble(0x46293e5939a08ceaL),
                    -Double.longBitsToDouble(0x46293e5939a08ceaL),
                    -Double.longBitsToDouble(0x46293e5939a08ceaL));
}
