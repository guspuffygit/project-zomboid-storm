package io.pzstorm.storm.jna.fmod.enums;

public enum FMOD_MODE {
    FMOD_DEFAULT(0x00000000L),
    FMOD_LOOP_OFF(0x00000001L),
    FMOD_LOOP_NORMAL(0x00000002L),
    FMOD_LOOP_BIDI(0x00000004L),
    FMOD_2D(0x00000008L),
    FMOD_3D(0x00000010L),
    FMOD_CREATESTREAM(0x00000080L),
    FMOD_CREATESAMPLE(0x00000100L),
    FMOD_CREATECOMPRESSEDSAMPLE(0x00000200L),
    FMOD_OPENUSER(0x00000400L),
    FMOD_OPENMEMORY(0x00000800L),
    FMOD_OPENMEMORY_POINT(0x10000000L),
    FMOD_OPENRAW(0x00001000L),
    FMOD_OPENONLY(0x00002000L),
    FMOD_ACCURATETIME(0x00004000L),
    FMOD_MPEGSEARCH(0x00008000L),
    FMOD_NONBLOCKING(0x00010000L),
    FMOD_UNIQUE(0x00020000L),
    FMOD_3D_HEADRELATIVE(0x00040000L),
    FMOD_3D_WORLDRELATIVE(0x00080000L),
    FMOD_3D_INVERSEROLLOFF(0x00100000L),
    FMOD_3D_LINEARROLLOFF(0x00200000L),
    FMOD_3D_LINEARSQUAREROLLOFF(0x00400000L),
    FMOD_3D_INVERSETAPEREDROLLOFF(0x00800000L),
    FMOD_3D_CUSTOMROLLOFF(0x04000000L),
    FMOD_3D_IGNOREGEOMETRY(0x40000000L),
    FMOD_IGNORETAGS(0x02000000L),
    FMOD_LOWMEM(0x08000000L),
    FMOD_VIRTUAL_PLAYFROMSTART(0x80000000L);

    private final long value;

    // Constructor to bind the hex value to the enum constant
    FMOD_MODE(long value) {
        this.value = value;
    }

    // Getter to retrieve the long value
    public long getValue() {
        return value;
    }

    public static long combineModes(FMOD_MODE... modes) {
        long totalMode = 0;

        // 2. Loop through every mode passed in
        if (modes != null) {
            for (FMOD_MODE m : modes) {
                // Accumulate the flags using Bitwise OR (|=)
                // Note: If FMOD_MODE is an Object/Enum, you likely need '.getValue()'
                // instead of just 'm'. E.g.: totalMode |= m.getValue();
                totalMode |= m.getValue();
            }
        }

        return totalMode;
    }
}
