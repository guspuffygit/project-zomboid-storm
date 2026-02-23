package io.pzstorm.storm.jna.fmod.ctypes;

import com.sun.jna.Structure;
import io.pzstorm.storm.jna.BaseStructure;
import java.util.List;

public class FMOD_FLOAT extends BaseStructure<Float> {
    public float value;

    @Override
    protected List<String> getFieldOrder() {
        return List.of("myValue");
    }

    public static class ByReference extends FMOD_FLOAT implements Structure.ByReference {}

    public static class ByValue extends FMOD_FLOAT implements Structure.ByValue {}

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @Override
    public Float getValue() {
        this.read();
        return value;
    }

    public static FMOD_FLOAT from(float value) {
        FMOD_FLOAT fmodFloat = new FMOD_FLOAT();
        fmodFloat.value = value;
        fmodFloat.write();
        return fmodFloat;
    }
}
