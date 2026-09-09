package io.github.antonschnfeld.drakon.graphics.command;

import java.util.Objects;

/**
 * Load/store operations for one depth attachment during a rendering scope.
 *
 * @param loadOp operation applied at rendering-scope start
 * @param storeOp operation applied at rendering-scope end
 * @param clearDepth depth clear value in {@code [0,1]}, used only when
 *        {@code loadOp == LoadOp.CLEAR}
 */
public record DepthAttachmentOps(LoadOp loadOp, StoreOp storeOp, float clearDepth) {
    /**
     * Validates depth attachment operations.
     *
     * @param loadOp depth load operation
     * @param storeOp depth store operation
     * @param clearDepth clear depth in {@code [0,1]}
     * @throws NullPointerException if {@code loadOp} or {@code storeOp} is null
     * @throws IllegalArgumentException if {@code clearDepth} is outside
     *         {@code [0,1]}
     */
    public DepthAttachmentOps {
        Objects.requireNonNull(loadOp, "loadOp");
        Objects.requireNonNull(storeOp, "storeOp");
        if (clearDepth < 0 || clearDepth > 1) {
            throw new IllegalArgumentException("clearDepth must be in [0,1]");
        }
    }

    /**
     * Creates operations that clear depth on load and preserve the result.
     *
     * @param depth clear depth in {@code [0,1]}
     * @return clear-and-store depth operations
     * @throws IllegalArgumentException if {@code depth} is outside {@code [0,1]}
     */
    public static DepthAttachmentOps clear(float depth) {
        return new DepthAttachmentOps(LoadOp.CLEAR, StoreOp.STORE, depth);
    }

    /**
     * Creates operations that preserve existing depth on load and store the
     * resulting depth afterward.
     *
     * @return load-and-store depth operations
     */
    public static DepthAttachmentOps load() {
        return new DepthAttachmentOps(LoadOp.LOAD, StoreOp.STORE, 1);
    }
}
