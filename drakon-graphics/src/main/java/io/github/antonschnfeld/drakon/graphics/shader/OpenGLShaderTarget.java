package io.github.antonschnfeld.drakon.graphics.shader;

import java.util.Objects;

/**
 * GLSL target for a desktop OpenGL core-profile device.
 *
 * <p>Iteration 5 deliberately standardizes the portable OpenGL path on GLSL.
 * Although sufficiently recent OpenGL implementations can ingest SPIR-V, that
 * path has a distinct SPIR-V execution environment and is not part of the core
 * contract yet.</p>
 *
 * @param majorVersion OpenGL major version
 * @param minorVersion OpenGL minor version
 * @param glslVersion core-profile GLSL language version encoded conventionally,
 *                    for example {@code 450} for GLSL 4.50 core
 */
public record OpenGLShaderTarget(
        int majorVersion,
        int minorVersion,
        int glslVersion
) implements ShaderTarget {
    /**
     * Validates target versions.
     *
     * @param majorVersion OpenGL major version
     * @param minorVersion OpenGL minor version
     * @param glslVersion GLSL version
     * @throws IllegalArgumentException if any version is outside its basic
     *         representable range
     */
    public OpenGLShaderTarget {
        if (majorVersion <= 0) throw new IllegalArgumentException("majorVersion must be positive");
        if (minorVersion < 0) throw new IllegalArgumentException("minorVersion must be non-negative");
        if (glslVersion <= 0) throw new IllegalArgumentException("glslVersion must be positive");
    }

    /**
     * Accepts GLSL source code.
     *
     * @param code shader code to test
     * @return {@code true} exactly for {@link GlslShaderCode}
     * @throws NullPointerException if {@code code} is {@code null}
     */
    @Override
    public boolean accepts(ShaderCode code) {
        Objects.requireNonNull(code, "code");
        return code instanceof GlslShaderCode;
    }
}
