package io.pzstorm.storm.jna.fmod.results;

import io.pzstorm.storm.jna.fmod.models.FmodVector;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class SystemGet3DListenerAttributesResult {
    private int result;
    private FmodVector position;
    private FmodVector velocity;
    private FmodVector forward;
    private FmodVector up;
}
