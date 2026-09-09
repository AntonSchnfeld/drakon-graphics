package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;

final class OpenGLGraphicsState extends OpenGLResource implements GraphicsState {
    final int program;
    final int vao;
    final GraphicsStateDescriptor descriptor;
    final OpenGLBindingPlan bindings;

    OpenGLGraphicsState(
            OpenGLDevice device,
            int program,
            int vao,
            GraphicsStateDescriptor descriptor,
            OpenGLBindingPlan bindings) {
        super(device);
        this.program = program;
        this.vao = vao;
        this.descriptor = descriptor;
        this.bindings = bindings;
    }

    @Override void deleteNative() {
        org.lwjgl.opengl.GL30C.glDeleteVertexArrays(vao);
        org.lwjgl.opengl.GL20C.glDeleteProgram(program);
    }
}
