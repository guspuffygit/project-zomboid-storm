package io.pzstorm.storm.patch.performance;

/**
 * {@link IndexedMapFieldPatch} for {@code zombie.characters.Moodles.moodles}: {@code
 * getMoodleLevel}/{@code isMaxMoodleLevel} become an array read, {@code Update} walks the values
 * view in registry order.
 */
public class MoodlesIndexedMapPatch extends IndexedMapFieldPatch {

    public MoodlesIndexedMapPatch() {
        super("zombie.characters.Moodles.Moodles", "moodles");
    }
}
