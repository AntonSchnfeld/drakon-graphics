package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.shader.OpenGLShaderTarget;

public final class ProbeOpenGLBackend extends AbstractProbeBackend {
    public ProbeOpenGLBackend() {
        super("opengl", "OpenGL probe", new OpenGLShaderTarget(4, 5, 450));
    }
}
