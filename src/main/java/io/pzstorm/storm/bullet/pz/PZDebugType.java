// Port of PZ glue PZDebugType (PZDebugLog.cpp static strings, _GLOBAL__sub_I_PZDebugLog.cpp
// @00148a10). The C++ type is a set of std::string constants passed as c_str().
package io.pzstorm.storm.bullet.pz;

public final class PZDebugType {

    private PZDebugType() {}

    public static final String Trace = "Trace";
    public static final String Noise = "Noise";
    public static final String Debug = "Debug";
    public static final String General = "General";
    public static final String Warning = "Warning";
    public static final String Error = "Error";
}
