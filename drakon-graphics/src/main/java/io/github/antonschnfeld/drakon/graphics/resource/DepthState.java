package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;

/**
 * Depth-test and depth-write state for graphics rendering.
 *
 * @param testEnabled whether depth testing is enabled
 * @param writeEnabled whether passing fragments write depth; depth testing must
 *        also be enabled when writes are enabled
 * @param compareOp comparison operation used when testing is enabled
 */
public record DepthState(boolean testEnabled, boolean writeEnabled, CompareOp compareOp) {
    /**
     * Validates portable depth-state combinations.
     *
     * @param testEnabled whether depth testing is enabled
     * @param writeEnabled whether depth writes are enabled
     * @param compareOp depth comparison operation
     * @throws NullPointerException if {@code compareOp} is {@code null}
     * @throws IllegalArgumentException if depth writes are enabled while depth
     *         testing is disabled
     */
    public DepthState {
        Objects.requireNonNull(compareOp, "compareOp");
        if (writeEnabled && !testEnabled) {
            throw new IllegalArgumentException("depth writes require depth testing to be enabled");
        }
    }

    /**
     * Returns state with depth testing and writes disabled.
     *
     * @return disabled depth state
     */
    public static DepthState disabled() {
        return new DepthState(false, false, CompareOp.ALWAYS);
    }

    /**
     * Returns conventional read/write depth state using a strict less comparison.
     *
     * @return standard depth state
     */
    public static DepthState standard() {
        return new DepthState(true, true, CompareOp.LESS);
    }

    /**
     * Returns read-only depth state suitable for passes that should test existing
     * depth without modifying it.
     *
     * @return read-only depth state
     */
    public static DepthState readOnly() {
        return new DepthState(true, false, CompareOp.LESS_OR_EQUAL);
    }
}
