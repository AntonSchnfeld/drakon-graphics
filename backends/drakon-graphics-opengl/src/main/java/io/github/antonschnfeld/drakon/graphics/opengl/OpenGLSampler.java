package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.Sampler;

final class OpenGLSampler extends OpenGLResource implements Sampler {
    final int handle;

    OpenGLSampler(OpenGLDevice device, int handle) {
        super(device);
        this.handle = handle;
    }

    @Override void deleteNative() { org.lwjgl.opengl.GL33C.glDeleteSamplers(handle); }
}
