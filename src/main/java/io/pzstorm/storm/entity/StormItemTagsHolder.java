package io.pzstorm.storm.entity;

import java.util.Set;

/**
 * Implemented onto {@code zombie.scripting.objects.Item} by {@code ItemTagMaskPatch}: the slot that
 * replaces the vanilla {@code itemTags} {@code HashSet} (every read of that field inside {@code
 * Item} is redirected to {@code StormItemTags.tagsOf}, which lazily fills this slot with a {@code
 * StormItemTagSet}).
 */
public interface StormItemTagsHolder {

    Set<Object> getStormItemTags();

    void setStormItemTags(Set<Object> tags);
}
