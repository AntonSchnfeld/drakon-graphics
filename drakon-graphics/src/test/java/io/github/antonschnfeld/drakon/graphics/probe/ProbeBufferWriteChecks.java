package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.command.Color;
import io.github.antonschnfeld.drakon.graphics.command.ColorAttachmentOps;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.command.RenderingInfo;
import io.github.antonschnfeld.drakon.graphics.resource.Buffer;
import io.github.antonschnfeld.drakon.graphics.resource.BufferDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.BufferUsage;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTarget;
import io.github.antonschnfeld.drakon.graphics.resource.RenderTargetDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.TextureUsage;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Hardware-free contract checks for ordered dynamic buffer writes. */
public final class ProbeBufferWriteChecks {
    private ProbeBufferWriteChecks() {}

    /** Runs the write contract against both portable probe personalities. */
    public static void run() {
        checkBackend(new ProbeOpenGLBackend());
        checkBackend(new ProbeVulkanBackend());
    }

    private static void checkBackend(GraphicsBackend backend) {
        selectedBytesAreSnapshotted(backend);
        writesPreserveStateAndOrder(backend);
        invalidRangesAreRejected(backend);
        invalidStatesAndScopesAreRejected(backend);
        ownershipAndLifecycleAreEnforced(backend);
    }

    private static void selectedBytesAreSnapshotted(GraphicsBackend backend) {
        try (ProbeGraphicsDevice device = device(backend);
                Buffer buffer = device.createBuffer(new BufferDescriptor(32, Set.of(BufferUsage.UNIFORM)));
                CommandEncoder commands = device.createCommandEncoder()) {
            commands.transition(buffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment allocation = arena.allocate(20);
                for (long i = 0; i < allocation.byteSize(); i++) {
                    allocation.setAtIndex(ValueLayout.JAVA_BYTE, i, (byte) (40 + i));
                }
                MemorySegment source = allocation.asSlice(4, 8);
                commands.writeBuffer(buffer, 8, source);
                source.fill((byte) 0);
            }

            try (CommandList list = commands.finish()) {
                ProbeCommandEncoder.ProbeBufferWrite write =
                        ((ProbeCommandList) list).bufferWrites().get(0);
                require(write.offset() == 8, "non-zero destination offset was not recorded");
                require(Arrays.equals(write.bytes(), new byte[] {44, 45, 46, 47, 48, 49, 50, 51}),
                        "recorded bytes did not preserve the selected source snapshot");
            }
        }
    }

    private static void writesPreserveStateAndOrder(GraphicsBackend backend) {
        for (ResourceState state : List.of(
                ResourceState.VERTEX_READ, ResourceState.INDEX_READ, ResourceState.UNIFORM_READ)) {
            BufferUsage usage = switch (state) {
                case VERTEX_READ -> BufferUsage.VERTEX;
                case INDEX_READ -> BufferUsage.INDEX;
                case UNIFORM_READ -> BufferUsage.UNIFORM;
                default -> throw new AssertionError("unexpected state " + state);
            };
            try (ProbeGraphicsDevice device = device(backend);
                    Buffer buffer = device.createBuffer(new BufferDescriptor(16, Set.of(usage)));
                    CommandEncoder commands = device.createCommandEncoder()) {
                commands.transition(buffer, ResourceState.UNDEFINED, state);
                commands.writeBuffer(buffer, 0, bytes(1, 2, 3, 4));
                require(device.bufferState(buffer) == state, "writeBuffer changed logical state " + state);
            }
        }

        try (ProbeGraphicsDevice device = device(backend);
                Buffer buffer = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
                CommandEncoder commands = device.createCommandEncoder()) {
            commands.transition(buffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            commands.writeBuffer(buffer, 0, bytes(1, 2, 3, 4, 5, 6, 7, 8));
            commands.writeBuffer(buffer, 4, bytes(9, 10, 11, 12));
            try (CommandList list = commands.finish()) {
                List<ProbeCommandEncoder.ProbeBufferWrite> writes =
                        ((ProbeCommandList) list).bufferWrites();
                require(writes.size() == 2, "write order was not retained");
                byte[] simulated = new byte[12];
                for (ProbeCommandEncoder.ProbeBufferWrite write : writes) {
                    System.arraycopy(write.bytes(), 0, simulated, Math.toIntExact(write.offset()), write.bytes().length);
                }
                require(Arrays.equals(simulated, new byte[] {1, 2, 3, 4, 9, 10, 11, 12, 0, 0, 0, 0}),
                        "later overlapping write did not win");
            }
        }
    }

    private static void invalidRangesAreRejected(GraphicsBackend backend) {
        try (ProbeGraphicsDevice device = device(backend);
                Buffer buffer = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
                Buffer huge = device.createBuffer(new BufferDescriptor(Long.MAX_VALUE, Set.of(BufferUsage.UNIFORM)));
                CommandEncoder commands = device.createCommandEncoder()) {
            commands.transition(buffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            commands.transition(huge, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            expect(IllegalArgumentException.class, () -> commands.writeBuffer(buffer, -4, bytes(1, 2, 3, 4)));
            expect(IllegalArgumentException.class, () -> commands.writeBuffer(buffer, 16, bytes(1, 2, 3, 4)));
            expect(IllegalArgumentException.class,
                    () -> commands.writeBuffer(huge, Long.MAX_VALUE - 3, bytes(1, 2, 3, 4)));
            expect(IllegalArgumentException.class, () -> commands.writeBuffer(buffer, 2, bytes(1, 2, 3, 4)));
            expect(IllegalArgumentException.class, () -> commands.writeBuffer(buffer, 0, bytes(1, 2, 3)));
            expect(IllegalArgumentException.class,
                    () -> commands.writeBuffer(buffer, 0, MemorySegment.ofArray(new byte[0])));
        }
    }

    private static void invalidStatesAndScopesAreRejected(GraphicsBackend backend) {
        try (ProbeGraphicsDevice device = device(backend);
                Buffer buffer = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
                CommandEncoder commands = device.createCommandEncoder()) {
            expect(IllegalStateException.class,
                    () -> commands.writeBuffer(buffer, 0, bytes(1, 2, 3, 4)));
        }

        try (ProbeGraphicsDevice device = device(backend);
                Buffer buffer = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
                Texture color = device.createTexture(new TextureDescriptor(
                        4, 4, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT)));
                RenderTarget target = device.createRenderTarget(new RenderTargetDescriptor(List.of(color), null));
                CommandEncoder commands = device.createCommandEncoder()) {
            commands.transition(buffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            commands.transition(color, ResourceState.UNDEFINED, ResourceState.COLOR_ATTACHMENT_WRITE);
            commands.beginRendering(RenderingInfo.builder(target)
                    .color(ColorAttachmentOps.clear(Color.BLACK))
                    .build());
            expect(IllegalStateException.class,
                    () -> commands.writeBuffer(buffer, 0, bytes(1, 2, 3, 4)));
            commands.endRendering();
        }
    }

    private static void ownershipAndLifecycleAreEnforced(GraphicsBackend backend) {
        try (ProbeGraphicsDevice first = device(backend); ProbeGraphicsDevice second = device(backend);
                Buffer foreign = first.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
                CommandEncoder commands = second.createCommandEncoder()) {
            expect(IllegalArgumentException.class,
                    () -> commands.writeBuffer(foreign, 0, bytes(1, 2, 3, 4)));
        }

        try (ProbeGraphicsDevice device = device(backend)) {
            Buffer closed = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
            closed.close();
            try (CommandEncoder commands = device.createCommandEncoder()) {
                expect(IllegalStateException.class,
                        () -> commands.writeBuffer(closed, 0, bytes(1, 2, 3, 4)));
            }

            Buffer buffer = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
            ProbeCommandEncoder aborted = (ProbeCommandEncoder) device.createCommandEncoder();
            aborted.transition(buffer, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            aborted.writeBuffer(buffer, 0, bytes(1, 2, 3, 4));
            aborted.close();
            require(aborted.terminal() && aborted.recordedBufferWriteCount() == 0,
                    "aborted encoder retained write data");

            ProbeCommandEncoder finished = (ProbeCommandEncoder) device.createCommandEncoder();
            finished.writeBuffer(buffer, 0, bytes(5, 6, 7, 8));
            ProbeCommandList abandoned = (ProbeCommandList) finished.finish();
            abandoned.close();
            require(abandoned.bufferWrites().isEmpty(), "closed command list retained write data");

            ProbeCommandEncoder submittedEncoder = (ProbeCommandEncoder) device.createCommandEncoder();
            submittedEncoder.writeBuffer(buffer, 0, bytes(9, 10, 11, 12));
            ProbeCommandList submitted = (ProbeCommandList) submittedEncoder.finish();
            device.submit(submitted);
            require(submitted.bufferWrites().isEmpty(), "submitted command list retained write data");

            Buffer doomed = device.createBuffer(new BufferDescriptor(16, Set.of(BufferUsage.UNIFORM)));
            ProbeCommandEncoder failingEncoder = (ProbeCommandEncoder) device.createCommandEncoder();
            failingEncoder.transition(doomed, ResourceState.UNDEFINED, ResourceState.UNIFORM_READ);
            failingEncoder.writeBuffer(doomed, 0, bytes(13, 14, 15, 16));
            ProbeCommandList failing = (ProbeCommandList) failingEncoder.finish();
            doomed.close();
            expect(IllegalStateException.class, () -> device.submit(failing));
            require(failing.terminal() && failing.bufferWrites().isEmpty(),
                    "failed submission retained write data");
        }
    }

    private static ProbeGraphicsDevice device(GraphicsBackend backend) {
        return (ProbeGraphicsDevice) backend.createDevice(GraphicsDeviceConfig.debug());
    }

    private static MemorySegment bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return MemorySegment.ofArray(result);
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
