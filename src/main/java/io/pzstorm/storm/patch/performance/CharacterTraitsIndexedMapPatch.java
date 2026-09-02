package io.pzstorm.storm.patch.performance;

/**
 * {@link IndexedMapFieldPatch} for {@code zombie.characters.traits.CharacterTraits.traits}: {@code
 * get(CharacterTrait)} becomes an array read; {@code getTraits()} still hands out a map that
 * iterates in first-insertion order, as the vanilla {@code LinkedHashMap} did.
 */
public class CharacterTraitsIndexedMapPatch extends IndexedMapFieldPatch {

    public CharacterTraitsIndexedMapPatch() {
        super("zombie.characters.traits.CharacterTraits", "traits");
    }
}
