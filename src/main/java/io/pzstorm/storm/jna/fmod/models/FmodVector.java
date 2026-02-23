package io.pzstorm.storm.jna.fmod.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class FmodVector {
    private float x;
    private float y;
    private float z;

    @Override
    public String toString() {
        return String.format("{%s,%s,%s}", x, y, z);
    }
}
