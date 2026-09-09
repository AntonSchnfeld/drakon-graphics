package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.Shader;
import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;

final class OpenGLShader extends OpenGLResource implements Shader {
    final int handle;
    private final ShaderStage stage;

    OpenGLShader(OpenGLDevice device, int handle, ShaderStage stage) {
        super(device);
        this.handle = handle;
        this.stage = stage;
    }

    @Override public ShaderStage stage() { requireAlive(); return stage; }
    @Override void deleteNative() { org.lwjgl.opengl.GL20C.glDeleteShader(handle); }
}
