package io.pzstorm.storm.patch.performance;

/** {@link RegistryKeyIndexPatch} for {@code zombie.characters.CharacterStat}. */
public class CharacterStatIndexPatch extends RegistryKeyIndexPatch {

    public CharacterStatIndexPatch() {
        super(
                "zombie.characters.CharacterStat",
                "io.pzstorm.storm.advice.registrykey.CharacterStatIndexAdvice");
    }
}
