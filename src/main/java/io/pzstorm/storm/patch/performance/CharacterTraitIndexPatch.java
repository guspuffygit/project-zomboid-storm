package io.pzstorm.storm.patch.performance;

/** {@link RegistryKeyIndexPatch} for {@code zombie.scripting.objects.CharacterTrait}. */
public class CharacterTraitIndexPatch extends RegistryKeyIndexPatch {

    public CharacterTraitIndexPatch() {
        super(
                "zombie.scripting.objects.CharacterTrait",
                "io.pzstorm.storm.advice.registrykey.CharacterTraitIndexAdvice");
    }
}
