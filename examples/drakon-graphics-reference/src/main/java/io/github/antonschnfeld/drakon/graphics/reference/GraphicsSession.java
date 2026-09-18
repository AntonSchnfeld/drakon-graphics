package io.github.antonschnfeld.drakon.graphics.reference;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLBackend;
import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLDevice;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanBackend;
import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanPresentation;
import org.lwjgl.opengl.GL;

/** Example-owned window, backend, device, and presentation bootstrap. */
final class GraphicsSession implements AutoCloseable {
    private final Main.Backend backend;
    private final GlfwPlatform platform;
    private final GlfwWindow window;
    private final GraphicsDevice device;
    private final RenderTarget presentationTarget;
    private boolean closed;

    private GraphicsSession(
            Main.Backend backend,
            GlfwPlatform platform,
            GlfwWindow window,
            GraphicsDevice device,
            RenderTarget presentationTarget) {
        this.backend = backend;
        this.platform = platform;
        this.window = window;
        this.device = device;
        this.presentationTarget = presentationTarget;
    }

    static GraphicsSession open(Main.Backend backend, int width, int height) {
        return switch (backend) {
            case OPENGL -> openOpenGL(width, height);
            case VULKAN -> openVulkan(width, height);
        };
    }

    private static GraphicsSession openOpenGL(int width, int height) {
        GlfwPlatform platform = new GlfwPlatform();
        GlfwWindow window = null;
        OpenGLDevice device = null;
        GlfwOpenGLRenderTarget target = null;
        try {
            window = platform.createOpenGLWindow(
                    width, height, "drakon-graphics 0.1 reference - OpenGL");
            window.makeContextCurrent();
            window.enableSwapInterval();
            GL.createCapabilities();

            OpenGLBackend backend = new OpenGLBackend();
            if (!backend.isSupported()) {
                throw new IllegalStateException("OpenGL 4.3 is unavailable");
            }
            device = backend.createDevice(GraphicsDeviceConfig.debug());
            target = new GlfwOpenGLRenderTarget(device, window);
            return new GraphicsSession(Main.Backend.OPENGL, platform, window, device, target);
        } catch (RuntimeException | Error failure) {
            if (target != null) target.close();
            if (device != null) device.close();
            if (window != null) window.close();
            platform.close();
            throw failure;
        }
    }

    private static GraphicsSession openVulkan(int width, int height) {
        GlfwPlatform platform = new GlfwPlatform();
        GlfwWindow window = null;
        GraphicsDevice device = null;
        RenderTarget target = null;
        try {
            window = platform.createVulkanWindow(
                    width, height, "drakon-graphics 0.1 reference - Vulkan");
            VulkanPresentation presentation = new VulkanBackend().createPresentationDevice(
                    GraphicsDeviceConfig.debug(), new GlfwVulkanSurfaceFactory(window));
            device = presentation.device();
            target = presentation.target();
            return new GraphicsSession(Main.Backend.VULKAN, platform, window, device, target);
        } catch (RuntimeException | Error failure) {
            if (target != null) target.close();
            if (device != null) device.close();
            if (window != null) window.close();
            platform.close();
            throw failure;
        }
    }

    Main.Backend backend() {
        return backend;
    }

    GraphicsDevice device() {
        requireOpen();
        return device;
    }

    RenderTarget presentationTarget() {
        requireOpen();
        return presentationTarget;
    }

    boolean shouldClose() {
        requireOpen();
        return window.shouldClose();
    }

    void processEvents() {
        requireOpen();
        window.pollEvents();
    }

    void waitForEvents() {
        requireOpen();
        window.waitEvents();
    }

    boolean hasRenderableFramebuffer() {
        requireOpen();
        return window.rawFramebufferWidth() > 0 && window.rawFramebufferHeight() > 0;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("graphics session is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        presentationTarget.close();
        device.close();
        window.close();
        platform.close();
    }
}
