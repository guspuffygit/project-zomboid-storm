package io.pzstorm.storm.scripting;

import io.pzstorm.storm.entity.StormItemTagsHolder;
import java.util.Set;

/**
 * Substitution target for every {@code this.itemTags} field read inside {@code
 * zombie.scripting.objects.Item} (the receiver arrives as the argument): hands back the item's
 * {@link StormItemTagSet}, creating it on first use. The vanilla {@code final HashSet} field is
 * still initialised by the constructor but never read again, so no write to a final field is
 * needed. If the holder interface is missing (patch not applied) the substitution is not applied
 * either, so this cannot be reached without it.
 */
public final class StormItemTags {

    private StormItemTags() {}

    public static Set<Object> tagsOf(Object item) {
        StormItemTagsHolder holder = (StormItemTagsHolder) item;
        Set<Object> tags = holder.getStormItemTags();
        if (tags == null) {
            tags = new StormItemTagSet();
            holder.setStormItemTags(tags);
        }
        return tags;
    }
}
