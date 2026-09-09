package io.github.antonschnfeld.drakon.graphics.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * SPIR-V shader code stored as an immutable byte snapshot.
 *
 * <p>The constructor copies the remaining bytes of the supplied buffer without
 * modifying its position or limit. {@link #code()} returns a fresh read-only
 * view each time, so callers cannot mutate the stored buffer state through the
 * returned value.</p>
 *
 * <p>This type only identifies the code representation. It does not claim that
 * the module targets Vulkan, OpenGL, or another SPIR-V execution environment.
 * Code passed to a device must have been produced for that device's
 * {@link ShaderTarget}.</p>
 *
 * @param code SPIR-V bytes; the remaining byte count must be positive and a
 *             multiple of four
 */
public record SpirvShaderCode(ByteBuffer code) implements ShaderCode {
    /**
     * Creates an immutable SPIR-V code snapshot.
     *
     * @param code source buffer whose remaining bytes are copied
     * @throws NullPointerException if {@code code} is {@code null}
     * @throws IllegalArgumentException if no bytes remain or the remaining byte
     *         count is not a multiple of four
     */
    public SpirvShaderCode {
        Objects.requireNonNull(code, "code");
        int remaining = code.remaining();
        if (remaining == 0 || (remaining & 3) != 0) {
            throw new IllegalArgumentException("SPIR-V byte count must be a positive multiple of four");
        }

        ByteBuffer source = code.duplicate();
        ByteBuffer copy = ByteBuffer.allocate(remaining).order(ByteOrder.LITTLE_ENDIAN);
        copy.put(source).flip();
        code = copy.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Returns a read-only view of the stored SPIR-V bytes.
     *
     * @return independent read-only buffer positioned at the first byte
     */
    @Override
    public ByteBuffer code() {
        ByteBuffer view = code.asReadOnlyBuffer().order(code.order());
        view.position(0);
        return view;
    }
}
