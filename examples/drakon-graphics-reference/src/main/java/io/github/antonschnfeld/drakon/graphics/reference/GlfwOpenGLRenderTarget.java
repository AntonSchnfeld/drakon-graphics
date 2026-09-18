package io.github.antonschnfeld.drakon.graphics.reference;

import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLDevice;
import io.github.antonschnfeld.drakon.graphics.opengl.OpenGLRenderTargetAccess;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;

import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL30C.*;

/** GLFW framebuffer-zero facade for an externally owned OpenGL context. */
final class GlfwOpenGLRenderTarget implements OpenGLRenderTargetAccess {
    private final OpenGLDevice device;
    private final GlfwWindow window;
    private final TextureFormat depthFormat;
    private boolean closed;

    GlfwOpenGLRenderTarget(OpenGLDevice device, GlfwWindow window) {
        this.device = device;
        this.window = window;
        depthFormat = verifyDefaultFramebufferDepth();
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
        return depthFormat;
    }

    @Override
    public void present() {
        requireOpen();
        glfwSwapBuffers(window.handle());
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("OpenGL presentation target is closed");
    }

    private static TextureFormat verifyDefaultFramebufferDepth() {
        int previous = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        try {
            int bits = glGetFramebufferAttachmentParameteri(
                    GL_DRAW_FRAMEBUFFER, GL_DEPTH, GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
            int componentType = glGetFramebufferAttachmentParameteri(
                    GL_DRAW_FRAMEBUFFER, GL_DEPTH, GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
            if (bits != 24 || componentType != GL_UNSIGNED_NORMALIZED) {
                throw new IllegalStateException(
                        "GLFW default framebuffer depth is not D24_UNORM: depthSize="
                                + bits + ", componentType=0x"
                                + Integer.toHexString(componentType));
            }
            return TextureFormat.D24_UNORM;
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previous);
        }
    }

    /** Closes only the facade; the GLFW window remains externally owned. */
    @Override
    public void close() {
        closed = true;
    }
}
