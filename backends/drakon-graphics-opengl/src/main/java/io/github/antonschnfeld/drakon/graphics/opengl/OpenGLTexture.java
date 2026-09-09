package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.TextureUsage;

import java.util.Set;

final class OpenGLTexture extends OpenGLResource implements Texture {
    final int handle;
    private final TextureDescriptor descriptor;
    ResourceState state = ResourceState.UNDEFINED;

    OpenGLTexture(OpenGLDevice device, int handle, TextureDescriptor descriptor) {
        super(device);
        this.handle = handle;
        this.descriptor = descriptor;
    }

    @Override public int width() { requireAlive(); return descriptor.width(); }
    @Override public int height() { requireAlive(); return descriptor.height(); }
    @Override public TextureFormat format() { requireAlive(); return descriptor.format(); }
    @Override public Set<TextureUsage> usage() { requireAlive(); return descriptor.usage(); }
    @Override void deleteNative() { org.lwjgl.opengl.GL11C.glDeleteTextures(handle); }
}
