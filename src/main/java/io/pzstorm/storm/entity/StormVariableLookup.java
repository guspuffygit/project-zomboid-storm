package io.pzstorm.storm.entity;

/**
 * Implemented onto the private nested class {@code
 * CharacterVariableCondition$CharacterVariableLookup} by {@code
 * CharacterVariableLookupAccessorPatch}, exposing its {@code variableReference} field ({@code
 * AnimationVariableReference}, typed {@code Object} here so the interface loads without game
 * classes) to {@code CharacterVariableResolveAdvice}, which resolves the slot typed instead of via
 * {@code getValueString()} + re-parse.
 */
public interface StormVariableLookup {

    Object getStormVariableReference();
}
