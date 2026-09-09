package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.ComputeState;
import io.github.antonschnfeld.drakon.graphics.resource.ComputeStateDescriptor;

final class OpenGLComputeState extends OpenGLResource implements ComputeState {
    final int program;
    final ComputeStateDescriptor descriptor;
    final OpenGLBindingPlan bindings;

    OpenGLComputeState(
            OpenGLDevice device,
            int program,
            ComputeStateDescriptor descriptor,
            OpenGLBindingPlan bindings) {
        super(device);
        this.program = program;
        this.descriptor = descriptor;
        this.bindings = bindings;
    }

    @Override void deleteNative() { org.lwjgl.opengl.GL20C.glDeleteProgram(program); }
}
