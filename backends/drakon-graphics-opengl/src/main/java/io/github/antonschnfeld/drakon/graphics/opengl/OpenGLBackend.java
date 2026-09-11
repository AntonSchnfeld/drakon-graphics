package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/**
 * LWJGL desktop OpenGL backend.
 *
 * <p>The generic {@link #createDevice(GraphicsDeviceConfig)} path creates a
 * small invisible GLFW window and owns its OpenGL context. This keeps the core
 * backend contract usable for offscreen graphics work without adding window
 * concepts to {@code drakon-graphics}. Applications that explicitly want a
 * GLFW-backed presentation target may use {@link #createWindowedDevice}.</p>
 *
 * <p>The GLFW convenience is backend-specific on purpose. Window bootstrap is
 * not part of the portable graphics API and may be replaced by another context
 * creation mechanism later without changing {@code drakon-graphics}.</p>
 */
public final class OpenGLBackend implements GraphicsBackend {
    /** Creates a stateless OpenGL backend service provider. */
    public OpenGLBackend() {}

    /** {@inheritDoc} */
    @Override
    public String id() {
        return "opengl";
    }

    /**
     * Performs a conservative library-level support check.
     *
     * <p>Creating a temporary GLFW context here would make this query invasive
     * and could interfere with GLFW state owned by another subsystem. The real
     * driver/context check therefore occurs in device creation.</p>
     *
     * @return {@code true} when the GLFW native library can be reached
     */
    @Override
    public boolean isSupported() {
        try {
            return GLFW.glfwGetVersionString() != null;
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Creates an OpenGL 4.3 core-profile device backed by an invisible GLFW
     * window.
     *
     * @param config device options
     * @return new offscreen-capable OpenGL device
     */
    @Override
    public GraphicsDevice createDevice(GraphicsDeviceConfig config) {
        return OpenGLDevice.create(Objects.requireNonNull(config, "config"), 1, 1, "drakon-graphics", false);
    }

    /**
     * Creates an OpenGL device whose default framebuffer is exposed as a
     * presentation-capable {@link RenderTarget}.
     *
     * <p>This method is intentionally not on {@link GraphicsBackend}: GLFW
     * window creation is backend/bootstrap integration rather than a portable
     * graphics operation.</p>
     *
     * @param config device options
     * @param width positive initial framebuffer width
     * @param height positive initial framebuffer height
     * @param title non-null window title
     * @return windowed OpenGL device
     * @throws IllegalArgumentException if dimensions are non-positive
     * @throws IllegalStateException if GLFW or the requested OpenGL context
     *         cannot be created
     */
    public OpenGLDevice createWindowedDevice(
            GraphicsDeviceConfig config,
            int width,
            int height,
            String title) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(title, "title");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window dimensions must be > 0");
        }
        return OpenGLDevice.create(config, width, height, title, true);
    }
}
