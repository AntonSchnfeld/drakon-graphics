package io.github.antonschnfeld.drakon.graphics.shader;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * SPIR-V shader code stored as an immutable byte snapshot.
 *
 * <p>The constructor copies the complete supplied segment into owned native
 * memory. Later caller mutation or closure of the source arena cannot alter the
 * stored bytes. {@link #code()} exposes that snapshot as a read-only segment.</p>
 *
 * <p>This type only identifies the code representation. It does not claim that
 * the module targets Vulkan, OpenGL, or another SPIR-V execution environment.
 * Code passed to a device must have been produced for that device's
 * {@link ShaderTarget}.</p>
 *
 * @param code SPIR-V bytes; the byte count must be positive and a multiple of four
 */
public record SpirvShaderCode(MemorySegment code) implements ShaderCode {
    /**
     * Creates an immutable SPIR-V code snapshot.
     *
     * @param code source segment whose complete contents are copied
     * @throws NullPointerException if {@code code} is {@code null}
     * @throws IllegalArgumentException if the byte count is zero or not a multiple
     *         of four
     */
    public SpirvShaderCode {
        Objects.requireNonNull(code, "code");
        long byteSize = code.byteSize();
        if (byteSize == 0 || (byteSize & 3) != 0) {
            throw new IllegalArgumentException("SPIR-V byte count must be a positive multiple of four");
        }
        MemorySegment snapshot = Arena.ofAuto().allocate(byteSize, Integer.BYTES);
        snapshot.copyFrom(code);
        code = snapshot.asReadOnly();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof SpirvShaderCode shaderCode
                && code.byteSize() == shaderCode.code.byteSize()
                && code.mismatch(shaderCode.code) == -1L;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (long index = code.byteSize(); index > 0; index--) {
            hash = 31 * hash + code.get(ValueLayout.JAVA_BYTE, index - 1);
        }
        return hash;
    }
}
