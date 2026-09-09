package io.github.antonschnfeld.drakon.graphics.backend;

/**
 * Options used when creating a {@link GraphicsDevice}.
 *
 * @param validation whether Drakon/backend validation checks should be enabled;
 *        native API validation facilities may additionally be enabled when the
 *        backend can do so
 */
public record GraphicsDeviceConfig(boolean validation) {
    /**
     * Returns the normal runtime configuration with validation disabled.
     *
     * @return the default device configuration
     */
    public static GraphicsDeviceConfig defaults() {
        return new GraphicsDeviceConfig(false);
    }

    /**
     * Returns a development configuration with validation enabled.
     *
     * @return a validation-enabled device configuration
     */
    public static GraphicsDeviceConfig debug() {
        return new GraphicsDeviceConfig(true);
    }
}
