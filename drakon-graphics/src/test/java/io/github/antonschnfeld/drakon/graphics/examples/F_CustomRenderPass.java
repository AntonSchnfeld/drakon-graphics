package io.github.antonschnfeld.drakon.graphics.examples;

import io.github.antonschnfeld.drakon.graphics.backend.*;
import io.github.antonschnfeld.drakon.graphics.command.*;
import io.github.antonschnfeld.drakon.graphics.pipeline.*;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackends;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.command.Color;
import io.github.antonschnfeld.drakon.graphics.command.ColorAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPass;
import io.github.antonschnfeld.drakon.graphics.pipeline.RenderPipeline;
import io.github.antonschnfeld.drakon.graphics.render.Renderer;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.shader.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public final class F_CustomRenderPass {
    public static final class WireframePass implements RenderPass {
        private final RenderView view;
        private final Texture color;
        private final GraphicsState state;
        private final Buffer vertices;
        private final Buffer indices;

        public WireframePass(RenderView view, Texture color, GraphicsState state, Buffer vertices, Buffer indices) {
            this.view = view;
            this.color = color;
            this.state = state;
            this.vertices = vertices;
            this.indices = indices;
        }

        @Override
        public void record(CommandEncoder commands) {
            commands.transition(color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
            commands.beginRendering(RenderingInfo.builder(view)
                    .color(ColorAttachmentOps.clear(Color.BLACK)).build());
            commands.setGraphicsState(state);
            commands.setVertexBuffer(0, vertices, 0);
            commands.setIndexBuffer(indices, IndexType.UINT32, 0);
            commands.drawIndexed(3, 1, 0, 0, 0);
            commands.endRendering();
        }
    }

    public static void run(String backendId) {
        GraphicsBackend backend = GraphicsBackends.require(backendId);

        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.debug())) {
            Buffer vertices = device.createBuffer(
                    new BufferDescriptor(3L * 3 * Float.BYTES, Set.of(BufferUsage.VERTEX)),
                    ByteBuffer.allocateDirect(3 * 3 * Float.BYTES));
            Buffer indices = device.createBuffer(
                    new BufferDescriptor(3L * Integer.BYTES, Set.of(BufferUsage.INDEX)),
                    ByteBuffer.allocateDirect(3 * Integer.BYTES));

            VertexLayout layout = VertexLayout.builder()
                    .binding(0, 3 * Float.BYTES, VertexInputRate.PER_VERTEX)
                    .attribute(0, 0, VertexFormat.FLOAT3, 0).build();
            Shader vs = shader(device, ShaderStage.VERTEX, "// wireframe vertex");
            Shader fs = shader(device, ShaderStage.FRAGMENT, "// wireframe fragment");
            GraphicsState wireframe = device.createGraphicsState(GraphicsStateDescriptor.builder()
                    .vertexShader(vs)
                    .fragmentShader(fs)
                    .vertexLayout(layout)
                    .raster(new RasterState(CullMode.NONE, true))
                    .colorFormat(TextureFormat.RGBA8_UNORM)
                    .build());

            Texture color = device.createTexture(new TextureDescriptor(
                    800, 600, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
            RenderTarget target = device.createRenderTarget(new RenderTargetDescriptor(List.of(color), null));
            RenderView view = RenderView.fullTarget(target);

            Renderer renderer = new Renderer(device);
            renderer.execute(RenderPipeline.of(new WireframePass(view, color, wireframe, vertices, indices)));
        }
    }
    /**
     * Supplies backend-targeted placeholder code because this project tests
     * drakon-graphics in isolation. Production code is expected to obtain the
     * same ShaderDescriptor from drakon-shaders (HLSL/Slang compiler layer).
     */
    private static Shader shader(GraphicsDevice device, ShaderStage stage, String label) {
        ShaderCode code;
        if (device.shaderTarget() instanceof OpenGLShaderTarget) {
            code = new GlslShaderCode("#version 450 core\nvoid main() {} // " + label);
        } else if (device.shaderTarget() instanceof VulkanShaderTarget) {
            // The probe validates representation routing, not SPIR-V semantics.
            ByteBuffer words = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            words.putInt(0x07230203).flip(); // SPIR-V magic word
            code = new SpirvShaderCode(words);
        } else {
            throw new IllegalStateException("Unsupported probe shader target: " + device.shaderTarget());
        }
        return device.createShader(new ShaderDescriptor(stage, "main", code));
    }

}
