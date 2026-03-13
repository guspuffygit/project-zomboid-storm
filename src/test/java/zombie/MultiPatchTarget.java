package zombie;

/**
 * Simple target class used by {@link io.pzstorm.storm.core.MultiPatchTransformerA} and {@link
 * io.pzstorm.storm.core.MultiPatchTransformerB} to verify that multiple transformers can be applied
 * to the same class.
 */
public class MultiPatchTarget {

    public static String getA() {
        return "original-a";
    }

    public static String getB() {
        return "original-b";
    }
}
