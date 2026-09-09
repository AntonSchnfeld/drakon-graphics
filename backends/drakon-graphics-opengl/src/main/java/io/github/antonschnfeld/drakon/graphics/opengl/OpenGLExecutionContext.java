package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.ComputeState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.IndexType;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;

import java.util.Map;

final class OpenGLExecutionContext {
    final OpenGLDevice device;
    OpenGLGraphicsState graphicsState;
    OpenGLComputeState computeState;
    OpenGLRenderTarget renderTarget;
    RenderingInfo renderingInfo;
    OpenGLBuffer indexBuffer;
    IndexType indexType;
    long indexOffset;
    final Map<Integer, VertexBufferBinding> vertexBuffers = new java.util.HashMap<>();

    OpenGLExecutionContext(OpenGLDevice device) {
        this.device = device;
    }

    record VertexBufferBinding(OpenGLBuffer buffer, long offset) {}
}
