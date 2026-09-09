package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.GpuResource;

abstract class OpenGLResource implements GpuResource {
    final OpenGLDevice device;
    private boolean closed;

    OpenGLResource(OpenGLDevice device) {
        this.device = device;
    }

    final void requireAlive() {
        if (closed) {
            throw new IllegalStateException("resource is closed");
        }
        device.requireOpen();
    }

    final boolean isClosed() {
        return closed;
    }

    @Override
    public final void close() {
        if (!closed) {
            closed = true;
            if (!device.isClosed()) {
                device.makeCurrent();
                deleteNative();
            }
        }
    }

    abstract void deleteNative();
}
