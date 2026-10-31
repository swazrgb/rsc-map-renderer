package openrsc.nav;

/**
 * Something a {@link NavPolicy} can price or prohibit by key. A registry transport constant is
 * one, standing for every arm of it; a door constant also hands out an {@link Arm} for each
 * direction.
 */
public interface TransportRef {

    /**
     * The key the policy stores: the constant's name, or {@code CONSTANT#arm} for one arm. Equal
     * refs give equal keys.
     */
    String key();

    record Arm(String key) implements TransportRef {}
}
