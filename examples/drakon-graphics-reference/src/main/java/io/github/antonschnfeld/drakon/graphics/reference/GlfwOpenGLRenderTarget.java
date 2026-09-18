package io.github.antonschnfeld.drakon.graphics.reference;

import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLDevice;
import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLRenderTargetAccess;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;

import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;

/** GLFW framebuffer-zero facade for an externally owned OpenGL context. */
final class GlfwOpenGLRenderTarget implements OpenGLRenderTargetAccess {
    private final OpenGLDevice device;
    private final GlfwWindow window;
    private boolean closed;

    GlfwOpenGLRenderTarget(OpenGLDevice device, GlfwWindow window) {
        this.device = device;
        this.window = window;
    }

    @Override
    public OpenGLDevice device() {
        return device;
    }

    @Override
    public int framebuffer() {
        requireOpen();
        return 0;
    }

    @Override
    public int width() {
        requireOpen();
        return window.renderableFramebufferWidth();
    }

    @Override
    public int height() {
        requireOpen();
        return window.renderableFramebufferHeight();
    }

    @Override
    public List<TextureFormat> colorFormats() {
        requireOpen();
        return List.of(TextureFormat.RGBA8_UNORM);
    }

    @Override
    public TextureFormat depthFormat() {
        requireOpen();
        return null;
    }

    @Override
    public void present() {
        requireOpen();
        glfwSwapBuffers(window.handle());
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("OpenGL presentation target is closed");
    }

    /** Closes only the facade; the GLFW window remains externally owned. */
    @Override
    public void close() {
        closed = true;
    }
}
