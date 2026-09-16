package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Objects;

/**
 * LWJGL OpenGL 4.3 backend for an externally owned current context.
 *
 * <p>Platform code owns context creation, current-context association, and
 * destruction. The context must be current on the calling thread while a
 * device is created and whenever the device is used. Closing the device only
 * releases OpenGL objects created by the device; it does not release or detach
 * the external context.</p>
 */
public final class OpenGLBackend implements GraphicsBackend {
    /** Creates a stateless OpenGL backend service provider. */
    public OpenGLBackend() {}

    /** {@inheritDoc} */
    @Override
    public String id() {
        return "opengl";
    }

    /** Reports whether the externally current context can support this backend. */
    @Override
    public boolean isSupported() {
        GLCapabilities previous = null;
        try {
            try {
                previous = GL.getCapabilities();
            } catch (IllegalStateException ignored) {
                // No LWJGL capabilities were associated with this thread.
            }
            GLCapabilities probed = GL.createCapabilities();
            return probed.OpenGL43;
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        } finally {
            GL.setCapabilities(previous);
        }
    }

    /**
     * Creates an OpenGL 4.3 device against the externally current context.
     *
     * @param config device options
     * @return new OpenGL device that does not own the current context
     * @throws IllegalStateException if no compatible context is current
     */
    @Override
    public OpenGLDevice createDevice(GraphicsDeviceConfig config) {
        return OpenGLDevice.create(Objects.requireNonNull(config, "config"));
    }
}
