package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.command.Color;
import io.github.antonschnfeld.drakon.graphics.command.ColorAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.resource.Binding;
import io.github.antonschnfeld.drakon.graphics.resource.BindingLayout;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSet;
import io.github.antonschnfeld.drakon.graphics.resource.BindingSetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferBinding;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsState;
import io.github.antonschnfeld.drakon.graphics.resource.GraphicsStateDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTargetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Shader;
import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.TextureUsage;
import io.github.antonschnfeld.drakon.graphics.resource.VertexFormat;
import io.github.antonschnfeld.drakon.graphics.resource.VertexInputRate;
import io.github.antonschnfeld.drakon.graphics.resource.VertexLayout;
import io.github.antonschnfeld.drakon.graphics.shader.GlslShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;

import java.util.List;
import java.util.Set;

/** Hardware-free checks for portable draw-state, ownership, and lifetime validation. */
public final class ProbeValidationParityChecks {
    private ProbeValidationParityChecks() {}

    /** Runs the portable validation parity checks against the strict probe device. */
    public static void run() {
        missingRequiredBindingIsRejected();
        completeRequiredBindingIsAccepted();
        compatibleSetSurvivesGraphicsStateChange();
        transitionedVertexBindingIsRevalidated();
        transitionedDescriptorResourceIsRevalidated();
        closedAndForeignResourcesAreRejected();
        bufferBindingBoundsRemainIndependent();
        transitionFromValidationFollowsConfig();
    }

    private static void missingRequiredBindingIsRejected() {
        try (Fixture fixture = new Fixture(true); CommandEncoder commands = fixture.device.createCommandEncoder()) {
            fixture.prepare(commands);
            commands.beginRendering(fixture.renderingInfo());
            commands.setGraphicsState(fixture.state);
            expect(IllegalStateException.class, () -> commands.draw(1, 1, 0, 0));
        }
    }

    private static void completeRequiredBindingIsAccepted() {
        try (Fixture fixture = new Fixture(true); CommandEncoder commands = fixture.device.createCommandEncoder()) {
            fixture.prepare(commands);
            commands.beginRendering(fixture.renderingInfo());
            commands.setGraphicsState(fixture.state);
            commands.bindSet(0, fixture.bindingSet);
            commands.draw(1, 1, 0, 0);
            commands.endRendering();
        }
    }

    private static void compatibleSetSurvivesGraphicsStateChange() {
        try (GraphicsDevice device = new ProbeOpenGLBackend().createDevice(GraphicsDeviceConfig.debug())) {
            Binding<BufferBinding> bindingA = Binding.uniformBuffer("a", 0, ShaderStage.VERTEX);
            Binding<BufferBinding> bindingB0 = Binding.uniformBuffer("b0", 0, ShaderStage.VERTEX);
            Binding<BufferBinding> bindingB1 = Binding.uniformBuffer("b1", 1, ShaderStage.VERTEX);
            Binding<BufferBinding> sharedBinding = Binding.uniformBuffer("shared", 0, ShaderStage.VERTEX);
            BindingLayout layoutA = BindingLayout.of(bindingA);
            BindingLayout layoutB = BindingLayout.of(bindingB0, bindingB1);
            BindingLayout sharedLayout = BindingLayout.of(sharedBinding);
            Buffer bufferA = uniformBuffer(device);
            Buffer bufferB0 = uniformBuffer(device);
            Buffer bufferB1 = uniformBuffer(device);
            Buffer sharedBuffer = uniformBuffer(device);
            BindingSet setA = bindingSet(device, layoutA, bindingA, bufferA);
            BindingSet setB = device.createBindingSet(BindingSetDescriptor.builder(layoutB)
                    .bind(bindingB0, BufferBinding.whole(bufferB0))
                    .bind(bindingB1, BufferBinding.whole(bufferB1))
                    .build());
            BindingSet sharedSet = bindingSet(
                    device, sharedLayout, sharedBinding, sharedBuffer);
            Texture color = device.createTexture(new TextureDescriptor(
                    8, 8, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
            RenderTarget target = device.createRenderTarget(
                    new RenderTargetDescriptor(List.of(color), null));
            Shader vertex = device.createShader(new ShaderDescriptor(
                    ShaderStage.VERTEX, "main", new GlslShaderCode("void main() {}")));
            Shader fragment = device.createShader(new ShaderDescriptor(
                    ShaderStage.FRAGMENT, "main", new GlslShaderCode("void main() {}")));
            GraphicsState stateA = state(device, vertex, fragment, layoutA, sharedLayout);
            GraphicsState stateB = state(device, vertex, fragment, layoutB, sharedLayout);

            try (CommandEncoder commands = device.createCommandEncoder()) {
                commands.transition(bufferA, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
                commands.transition(bufferB0, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
                commands.transition(bufferB1, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
                commands.transition(sharedBuffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
                commands.transition(color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
                commands.beginRendering(RenderingInfo.builder(target)
                        .color(ColorAttachmentOps.clear(Color.BLACK))
                        .build());
                commands.setGraphicsState(stateA);
                commands.bindSet(0, setA);
                commands.bindSet(1, sharedSet);
                commands.setGraphicsState(stateB);
                commands.bindSet(0, setB);
                commands.draw(1, 1, 0, 0);
                commands.endRendering();
            }
        }
    }

    private static Buffer uniformBuffer(GraphicsDevice device) {
        return device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
    }

    private static BindingSet bindingSet(
            GraphicsDevice device,
            BindingLayout layout,
            Binding<BufferBinding> binding,
            Buffer buffer) {
        return device.createBindingSet(BindingSetDescriptor.builder(layout)
                .bind(binding, BufferBinding.whole(buffer))
                .build());
    }

    private static GraphicsState state(
            GraphicsDevice device,
            Shader vertex,
            Shader fragment,
            BindingLayout first,
            BindingLayout shared) {
        return device.createGraphicsState(GraphicsStateDescriptor.builder()
                .vertexShader(vertex)
                .fragmentShader(fragment)
                .bindingLayout(first)
                .bindingLayout(shared)
                .colorFormat(TextureFormat.RGBA8_UNORM)
                .build());
    }

    private static void transitionedVertexBindingIsRevalidated() {
        try (Fixture fixture = new Fixture(false); CommandEncoder commands = fixture.device.createCommandEncoder()) {
            Buffer vertex = fixture.device.createBuffer(new BufferDescriptor(
                    16, Set.of(BufferUsage.VERTEX, BufferUsage.UNIFORM)));
            commands.setGraphicsState(fixture.state);
            commands.setVertexBuffer(0, vertex, 0);
            commands.transition(vertex, ResourceState.UNDEFINED, ResourceState.VERTEX_READ);
            commands.transition(vertex, ResourceState.VERTEX_READ, ResourceState.UNIFORM_READ);
            commands.transition(fixture.color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
            commands.beginRendering(fixture.renderingInfo());
            expect(IllegalStateException.class, () -> commands.draw(1, 1, 0, 0));
        }
    }

    private static void transitionedDescriptorResourceIsRevalidated() {
        try (Fixture fixture = new Fixture(true); CommandEncoder commands = fixture.device.createCommandEncoder()) {
            commands.setGraphicsState(fixture.state);
            commands.bindSet(0, fixture.bindingSet);
            commands.transition(fixture.uniform, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            commands.transition(fixture.uniform, ResourceState.UNIFORM_READ, ResourceState.VERTEX_READ);
            commands.transition(fixture.color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
            commands.beginRendering(fixture.renderingInfo());
            expect(IllegalStateException.class, () -> commands.draw(1, 1, 0, 0));
        }
    }

    private static void closedAndForeignResourcesAreRejected() {
        try (Fixture first = new Fixture(false); Fixture second = new Fixture(false)) {
            try (CommandEncoder commands = second.device.createCommandEncoder()) {
                expect(IllegalArgumentException.class, () -> commands.setGraphicsState(first.state));
            }

            first.state.close();
            try (CommandEncoder commands = first.device.createCommandEncoder()) {
                expect(IllegalStateException.class, () -> commands.setGraphicsState(first.state));
            }

            first.target.close();
            try (CommandEncoder commands = first.device.createCommandEncoder()) {
                expect(IllegalStateException.class, () -> commands.beginRendering(first.renderingInfo()));
            }
        }
    }

    private static void bufferBindingBoundsRemainIndependent() {
        try (Fixture fixture = new Fixture(false)) {
            new BufferBinding(fixture.uniform, 0, 16);
            expect(IllegalArgumentException.class,
                    () -> new BufferBinding(fixture.uniform, 8, 9));
        }
    }

    private static void transitionFromValidationFollowsConfig() {
        transitionFromValidationFollowsConfig(new ProbeOpenGLBackend());
        transitionFromValidationFollowsConfig(new ProbeVulkanBackend());
    }

    private static void transitionFromValidationFollowsConfig(GraphicsBackend backend) {
        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.defaults());
                CommandEncoder commands = device.createCommandEncoder()) {
            Buffer buffer = device.createBuffer(new BufferDescriptor(
                    16, Set.of(BufferUsage.VERTEX, BufferUsage.UNIFORM)));
            commands.transition(buffer, ResourceState.VERTEX_READ, ResourceState.UNIFORM_READ);
        }
        try (GraphicsDevice device = backend.createDevice(GraphicsDeviceConfig.debug());
                CommandEncoder commands = device.createCommandEncoder()) {
            Buffer buffer = device.createBuffer(new BufferDescriptor(
                    16, Set.of(BufferUsage.VERTEX, BufferUsage.UNIFORM)));
            expect(IllegalStateException.class, () -> commands.transition(
                    buffer, ResourceState.VERTEX_READ, ResourceState.UNIFORM_READ));
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static final class Fixture implements AutoCloseable {
        final GraphicsDevice device = new ProbeOpenGLBackend().createDevice(GraphicsDeviceConfig.debug());
        final Texture color = device.createTexture(new TextureDescriptor(
                8, 8, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
        final RenderTarget target = device.createRenderTarget(new RenderTargetDescriptor(List.of(color), null));
        final Buffer uniform = device.createBuffer(new BufferDescriptor(
                16, Set.of(BufferUsage.UNIFORM, BufferUsage.VERTEX)));
        final Binding<BufferBinding> binding = Binding.uniformBuffer("globals", 0, ShaderStage.VERTEX);
        final BindingLayout bindingLayout;
        final BindingSet bindingSet;
        final GraphicsState state;

        Fixture(boolean resources) {
            bindingLayout = resources ? BindingLayout.of(binding) : null;
            bindingSet = resources
                    ? device.createBindingSet(BindingSetDescriptor.builder(bindingLayout)
                            .bind(binding, BufferBinding.whole(uniform))
                            .build())
                    : null;
            Shader vertex = device.createShader(new ShaderDescriptor(
                    ShaderStage.VERTEX, "main", new GlslShaderCode("void main() {}")));
            Shader fragment = device.createShader(new ShaderDescriptor(
                    ShaderStage.FRAGMENT, "main", new GlslShaderCode("void main() {}")));
            GraphicsStateDescriptor.Builder descriptor = GraphicsStateDescriptor.builder()
                    .vertexShader(vertex)
                    .fragmentShader(fragment)
                    .colorFormat(TextureFormat.RGBA8_UNORM);
            if (resources) descriptor.bindingLayout(bindingLayout);
            else descriptor.vertexLayout(VertexLayout.builder()
                    .binding(0, 16, VertexInputRate.PER_VERTEX)
                    .attribute(0, 0, VertexFormat.FLOAT4, 0)
                    .build());
            state = device.createGraphicsState(descriptor.build());
        }

        void prepare(CommandEncoder commands) {
            commands.transition(uniform, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            commands.transition(color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
        }

        RenderingInfo renderingInfo() {
            return RenderingInfo.builder(target).color(ColorAttachmentOps.clear(Color.BLACK)).build();
        }

        @Override
        public void close() {
            device.close();
        }
    }
}
