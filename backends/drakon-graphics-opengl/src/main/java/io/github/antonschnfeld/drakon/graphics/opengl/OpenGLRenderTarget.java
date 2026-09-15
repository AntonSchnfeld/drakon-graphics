package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;

import java.util.List;

final class OpenGLRenderTarget extends OpenGLResource implements OpenGLRenderTargetAccess {
    final int framebuffer;
    private final List<OpenGLTexture> colors;
    private final OpenGLTexture depth;
    private final int fixedWidth;
    private final int fixedHeight;
    private final List<TextureFormat> colorFormats;
    private final TextureFormat depthFormat;

    OpenGLRenderTarget(
            OpenGLDevice device,
            int framebuffer,
            List<OpenGLTexture> colors,
            OpenGLTexture depth,
            int width,
            int height,
            List<TextureFormat> colorFormats,
            TextureFormat depthFormat) {
        super(device);
        this.framebuffer = framebuffer;
        this.colors = List.copyOf(colors);
        this.depth = depth;
        fixedWidth = width;
        fixedHeight = height;
        this.colorFormats = List.copyOf(colorFormats);
        this.depthFormat = depthFormat;
    }

    List<OpenGLTexture> colors() { return colors; }
    OpenGLTexture depth() { return depth; }

    void requireAttachmentsAlive() {
        for (OpenGLTexture color : colors) color.requireAlive();
        if (depth != null) depth.requireAlive();
    }

    @Override public OpenGLDevice device() { return device; }
    @Override public int framebuffer() { requireAlive(); return framebuffer; }

    @Override
    public void present() {
        requireAlive();
        throw new IllegalArgumentException("render target has no presentation integration");
    }

    @Override
    public int width() {
        requireAlive();
        return fixedWidth;
    }

    @Override
    public int height() {
        requireAlive();
        return fixedHeight;
    }

    @Override public List<TextureFormat> colorFormats() { requireAlive(); return colorFormats; }
    @Override public TextureFormat depthFormat() { requireAlive(); return depthFormat; }

    @Override void deleteNative() {
        if (framebuffer != 0) {
            org.lwjgl.opengl.GL30C.glDeleteFramebuffers(framebuffer);
        }
    }
}
