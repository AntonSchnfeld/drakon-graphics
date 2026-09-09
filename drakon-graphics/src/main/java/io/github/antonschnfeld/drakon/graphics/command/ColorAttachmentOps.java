package io.github.antonschnfeld.drakon.graphics.command;

import java.util.Objects;

/**
 * Load/store operations for one color attachment during a rendering scope.
 *
 * @param loadOp operation applied to the attachment at rendering-scope start
 * @param storeOp operation applied to the attachment at rendering-scope end
 * @param clearColor clear value used only when {@code loadOp == LoadOp.CLEAR};
 *        ignored for other load operations
 */
public record ColorAttachmentOps(LoadOp loadOp, StoreOp storeOp, Color clearColor) {
    /**
     * Validates color attachment operations.
     *
     * @param loadOp attachment load operation
     * @param storeOp attachment store operation
     * @param clearColor clear color, retained even when the load operation does
     *        not consume it
     * @throws NullPointerException if any argument is {@code null}
     */
    public ColorAttachmentOps {
        Objects.requireNonNull(loadOp, "loadOp");
        Objects.requireNonNull(storeOp, "storeOp");
        Objects.requireNonNull(clearColor, "clearColor");
    }

    /**
     * Creates operations that clear on load and preserve the result.
     *
     * @param color clear color
     * @return clear-and-store attachment operations
     * @throws NullPointerException if {@code color} is {@code null}
     */
    public static ColorAttachmentOps clear(Color color) {
        return new ColorAttachmentOps(LoadOp.CLEAR, StoreOp.STORE, color);
    }

    /**
     * Creates operations that preserve existing contents on load and store the
     * resulting contents afterward.
     *
     * @return load-and-store attachment operations
     */
    public static ColorAttachmentOps load() {
        return new ColorAttachmentOps(LoadOp.LOAD, StoreOp.STORE, Color.BLACK);
    }
}
