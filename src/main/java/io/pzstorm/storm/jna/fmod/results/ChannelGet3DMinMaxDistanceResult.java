package io.pzstorm.storm.jna.fmod.results;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class ChannelGet3DMinMaxDistanceResult {
    private int result;
    private float minDistance;
    private float maxDistance;
}
