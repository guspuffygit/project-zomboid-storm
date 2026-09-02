package io.pzstorm.storm.patch.performance;

/** {@link RegistryKeyIndexPatch} for {@code zombie.scripting.objects.MoodleType}. */
public class MoodleTypeIndexPatch extends RegistryKeyIndexPatch {

    public MoodleTypeIndexPatch() {
        super(
                "zombie.scripting.objects.MoodleType",
                "io.pzstorm.storm.advice.registrykey.MoodleTypeIndexAdvice");
    }
}
