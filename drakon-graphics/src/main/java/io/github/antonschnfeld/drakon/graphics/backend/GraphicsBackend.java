package io.github.antonschnfeld.drakon.graphics.backend;

/**
 * Service-provider abstraction for one graphics API backend.
 *
 * <p>A backend represents an implementation family such as OpenGL or Vulkan;
 * it is not itself a logical GPU device. Applications normally obtain backend
 * instances through {@link GraphicsBackends} and then create one or more
 * {@link GraphicsDevice}s from the selected backend.</p>
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader}.
 * A provider should therefore be cheap to instantiate and should defer costly
 * native initialization until {@link #createDevice(GraphicsDeviceConfig)}.</p>
 */
public interface GraphicsBackend {
    /**
     * Returns the stable identifier used to select this backend.
     *
     * <p>Identifiers are compared case-insensitively by {@link GraphicsBackends}
     * and must be unique among installed backend providers. Implementations
     * should use short, lowercase identifiers such as {@code "opengl"} or
     * {@code "vulkan"}.</p>
     *
     * @return the non-null, non-blank backend identifier
     */
    String id();

    /**
     * Reports whether this backend can create a device in the current process
     * and environment.
     *
     * <p>This method may probe native-library or driver availability but should
     * not create a persistent graphics device or other long-lived GPU state.</p>
     *
     * @return {@code true} when this backend is currently usable
     */
    boolean isSupported();

    /**
     * Creates a logical graphics device using this backend.
     *
     * @param config device creation options; must not be {@code null}
     * @return a newly created device owned by the caller
     * @throws NullPointerException if {@code config} is {@code null}
     * @throws IllegalStateException if the backend cannot create a device in
     *         the current environment
     */
    GraphicsDevice createDevice(GraphicsDeviceConfig config);
}
