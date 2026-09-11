package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;

/**
 * Fixed-function rasterization options.
 *
 * <p>Counter-clockwise primitive winding is defined as front-facing throughout
 * the portable API.</p>
 *
 * @param cullMode triangle-face culling mode
 */
public record RasterState(CullMode cullMode) {
    /**
     * Validates raster state.
     *
     * @param cullMode culling mode
     * @throws NullPointerException if {@code cullMode} is {@code null}
     */
    public RasterState {
        Objects.requireNonNull(cullMode, "cullMode");
    }

    /**
     * Returns filled back-face-culling rasterization state.
     *
     * @return standard raster state
     */
    public static RasterState standard() {
        return new RasterState(CullMode.BACK);
    }
}
