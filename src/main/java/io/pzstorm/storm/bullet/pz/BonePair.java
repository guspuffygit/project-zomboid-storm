// Port of PZ glue BonePair (8 bytes: two SkeletonBone::Type ints).
package io.pzstorm.storm.bullet.pz;

public class BonePair {
    /** +0 */
    public int first;

    /** +4 */
    public int second;

    public BonePair(int first, int second) {
        this.first = first;
        this.second = second;
    }
}
