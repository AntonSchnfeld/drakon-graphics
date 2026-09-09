package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;

import java.util.List;

final class OpenGLRenderTarget extends OpenGLResource implements RenderTarget {
    final int framebuffer;
    private final List<OpenGLTexture> colors;
    private final OpenGLTexture depth;
    private final boolean presentable;
    private final long window;
    private final int fixedWidth;
    private final int fixedHeight;
    private final List<TextureFormat> colorFormats;
    private final TextureFormat depthFormat;

    OpenGLRenderTarget(
            OpenGLDevice device,
            int framebuffer,
            List<OpenGLTexture> colors,
            OpenGLTexture depth,
            boolean presentable,
            long window,
            int width,
            int height,
            List<TextureFormat> colorFormats,
            TextureFormat depthFormat) {
        super(device);
        this.framebuffer = framebuffer;
        this.colors = List.copyOf(colors);
        this.depth = depth;
        this.presentable = presentable;
        this.window = window;
        fixedWidth = width;
        fixedHeight = height;
        this.colorFormats = List.copyOf(colorFormats);
        this.depthFormat = depthFormat;
    }

    boolean presentable() { return presentable; }
    long window() { return window; }
    List<OpenGLTexture> colors() { return colors; }
    OpenGLTexture depth() { return depth; }

    @Override
    public int width() {
        requireAlive();
        return presentable ? device.framebufferWidth(window) : fixedWidth;
    }

    @Override
    public int height() {
        requireAlive();
        return presentable ? device.framebufferHeight(window) : fixedHeight;
    }

    @Override public List<TextureFormat> colorFormats() { requireAlive(); return colorFormats; }
    @Override public TextureFormat depthFormat() { requireAlive(); return depthFormat; }

    @Override void deleteNative() {
        if (framebuffer != 0) {
            org.lwjgl.opengl.GL30C.glDeleteFramebuffers(framebuffer);
        }
    }
}
