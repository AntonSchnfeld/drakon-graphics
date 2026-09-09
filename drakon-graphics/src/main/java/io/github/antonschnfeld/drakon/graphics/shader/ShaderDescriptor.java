package io.github.antonschnfeld.drakon.graphics.shader;

import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;

import java.util.Objects;

/**
 * Device shader creation input.
 *
 * <p>The descriptor contains shader semantics and code representation but no
 * notion of whether that code is "compiled". A GLSL value may be handwritten
 * or generated from HLSL/Slang, while SPIR-V may still undergo native driver
 * translation when a device creates its shader resource.</p>
 *
 * <p>The supplied {@link ShaderCode} must be suitable for the consuming
 * device's {@link ShaderTarget}. The core graphics API performs no source
 * language translation; that responsibility belongs to a separate shader
 * compiler/toolchain layer.</p>
 *
 * @param stage shader execution stage
 * @param entryPoint non-blank entry-point name used by the target representation
 * @param code backend-targeted shader code
 */
public record ShaderDescriptor(
        ShaderStage stage,
        String entryPoint,
        ShaderCode code
) {
    /**
     * Validates shader creation input.
     *
     * @param stage shader stage
     * @param entryPoint shader entry point
     * @param code shader code
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code entryPoint} is blank
     */
    public ShaderDescriptor {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(entryPoint, "entryPoint");
        Objects.requireNonNull(code, "code");
        if (entryPoint.isBlank()) throw new IllegalArgumentException("entryPoint must not be blank");
    }
}
