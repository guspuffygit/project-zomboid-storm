package io.pzstorm.storm.jna.fmod.results;

import io.pzstorm.storm.jna.fmod.models.FmodVector;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class ChannelGet3DConeOrientationResult {
    private int result;
    private FmodVector vector;
}
