package io.pzstorm.storm.jna.fmod.ctypes;

import io.pzstorm.storm.jna.BaseStructure;
import io.pzstorm.storm.jna.fmod.models.FmodVector;
import java.util.Arrays;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Setter
public class FMOD_VECTOR extends BaseStructure<FmodVector> {

    public float x;
    public float y;
    public float z;

    public FMOD_VECTOR(FmodVector vector) {
        this.x = vector.getX();
        this.y = vector.getY();
        this.z = vector.getZ();
    }

    // JNA requires strict field ordering
    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("x", "y", "z");
    }

    @Override
    public FmodVector getValue() {
        this.read();
        return new FmodVector(this.x, this.y, this.z);
    }

    public static FMOD_VECTOR from(FmodVector vector) {
        FMOD_VECTOR nativeVector = new FMOD_VECTOR();
        nativeVector.x = vector.getX();
        nativeVector.y = vector.getY();
        nativeVector.z = vector.getZ();
        nativeVector.write();
        return nativeVector;
    }
}
