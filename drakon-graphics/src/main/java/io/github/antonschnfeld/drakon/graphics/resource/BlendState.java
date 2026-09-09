package io.github.antonschnfeld.drakon.graphics.resource;

/**
 * Minimal color blending state.
 *
 * <p>The current API intentionally exposes only opaque writes and conventional
 * source-alpha blending. When enabled, color uses source alpha and one-minus-
 * source-alpha factors. More general blend equations are deliberately deferred
 * until a real rendering requirement demands them.</p>
 *
 * @param enabled whether conventional alpha blending is enabled
 */
public record BlendState(boolean enabled) {
    /**
     * Returns opaque color-write behavior with blending disabled.
     *
     * @return opaque blend state
     */
    public static BlendState opaque() {
        return new BlendState(false);
    }

    /**
     * Returns conventional source-alpha blending.
     *
     * @return alpha-blending state
     */
    public static BlendState alphaBlend() {
        return new BlendState(true);
    }
}
