package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;

import java.util.Set;

final class OpenGLBuffer extends OpenGLResource implements Buffer {
    final int handle;
    private final long size;
    private final Set<BufferUsage> usage;
    ResourceState state = ResourceState.UNDEFINED;

    OpenGLBuffer(OpenGLDevice device, int handle, BufferDescriptor descriptor) {
        super(device);
        this.handle = handle;
        size = descriptor.size();
        usage = descriptor.usage();
    }

    @Override public long size() { requireAlive(); return size; }
    @Override public Set<BufferUsage> usage() { requireAlive(); return usage; }
    @Override void deleteNative() { org.lwjgl.opengl.GL15C.glDeleteBuffers(handle); }
}
