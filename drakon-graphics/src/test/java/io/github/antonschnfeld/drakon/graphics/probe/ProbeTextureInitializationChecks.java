package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.TextureUsage;

import java.nio.ByteBuffer;
import java.util.Set;

/** Probe-only contract checks for creation-time texture initialization. */
public final class ProbeTextureInitializationChecks {
    private ProbeTextureInitializationChecks() {}

    /** Runs texture-initialization checks that require probe state inspection. */
    public static void run() {
        ProbeGraphicsDevice device = new ProbeGraphicsDevice("test", true,
                new io.github.antonschnfeld.drakon.graphics.shader.OpenGLShaderTarget(4, 6, 460));
        TextureDescriptor sampled = new TextureDescriptor(2, 2, TextureFormat.RGBA8_UNORM,
                Set.of(TextureUsage.SAMPLED));
        ByteBuffer data = ByteBuffer.allocate(20);
        data.position(2).limit(18);
        int position = data.position();
        int limit = data.limit();

        expect(NullPointerException.class, () -> device.createTexture(null, data, ResourceState.SAMPLED_READ));
        expect(NullPointerException.class, () -> device.createTexture(sampled, null, ResourceState.SAMPLED_READ));
        expect(NullPointerException.class, () -> device.createTexture(sampled, data, null));
        ByteBuffer tooSmall = ByteBuffer.allocate(15);
        expect(IllegalArgumentException.class, () -> device.createTexture(sampled, tooSmall, ResourceState.SAMPLED_READ));
        ByteBuffer tooLarge = ByteBuffer.allocate(17);
        expect(IllegalArgumentException.class, () -> device.createTexture(sampled, tooLarge, ResourceState.SAMPLED_READ));
        expect(IllegalArgumentException.class, () -> device.createTexture(sampled, data, ResourceState.UNDEFINED));
        expect(IllegalArgumentException.class, () -> device.createTexture(sampled, data, ResourceState.VERTEX_READ));
        expect(IllegalArgumentException.class, () -> device.createTexture(sampled, data, ResourceState.COLOR_ATTACHMENT_WRITE));
        TextureDescriptor depth = new TextureDescriptor(2, 2, TextureFormat.D32_FLOAT,
                Set.of(TextureUsage.DEPTH_ATTACHMENT));
        expect(IllegalArgumentException.class, () -> device.createTexture(
                depth, ByteBuffer.allocate(16), ResourceState.DEPTH_ATTACHMENT_WRITE));

        Texture initialized = device.createTexture(sampled, data, ResourceState.SAMPLED_READ);
        if (device.textureState(initialized) != ResourceState.SAMPLED_READ) {
            throw new AssertionError("initialized texture must start in its requested state");
        }
        if (data.position() != position || data.limit() != limit) {
            throw new AssertionError("createTexture must preserve ByteBuffer position and limit");
        }
        Texture uninitialized = device.createTexture(sampled);
        if (device.textureState(uninitialized) != ResourceState.UNDEFINED) {
            throw new AssertionError("uninitialized texture must start in UNDEFINED");
        }
        device.close();
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName(), failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
