package io.github.antonschnfeld.drakon.graphics.resource;

/** Portable texture formats currently exposed by the core API. */
public enum TextureFormat {
    /** Four 8-bit normalized unsigned color channels. */
    RGBA8_UNORM(false),
    /** Four 8-bit normalized unsigned color channels in blue-green-red-alpha storage order. */
    BGRA8_UNORM(false),
    /** Four 16-bit floating-point color channels. */
    RGBA16_FLOAT(false),
    /** 32-bit floating-point depth format. */
    D32_FLOAT(true);

    private final boolean depth;

    TextureFormat(boolean depth) {
        this.depth = depth;
    }

    /**
     * Reports whether this is a depth rather than color format.
     *
     * @return {@code true} for depth formats
     */
    public boolean isDepth() {
        return depth;
    }
}
