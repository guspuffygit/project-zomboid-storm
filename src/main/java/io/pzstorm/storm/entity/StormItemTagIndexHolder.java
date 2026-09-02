package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.scripting.objects.ItemTag} by {@code ItemTagIndexPatch}: every tag
 * gets a dense, process-unique index at construction, which {@code StormItemTagSet} uses as its bit
 * position. Tags are identity-compared in vanilla (no {@code equals}/{@code hashCode}), so the
 * index is exactly as discriminating as the identity {@code HashSet} it replaces.
 */
public interface StormItemTagIndexHolder {

    int getStormIndex();
}
