package io.github.antonschnfeld.drakon.graphics.shader;

import java.util.Objects;

/**
 * SPIR-V target for a Vulkan device.
 *
 * @param majorVersion Vulkan major version
 * @param minorVersion Vulkan minor version
 * @param spirvMajorVersion SPIR-V major version accepted by the target
 * @param spirvMinorVersion SPIR-V minor version accepted by the target
 */
public record VulkanShaderTarget(
        int majorVersion,
        int minorVersion,
        int spirvMajorVersion,
        int spirvMinorVersion
) implements ShaderTarget {
    /**
     * Validates target versions.
     *
     * @param majorVersion Vulkan major version
     * @param minorVersion Vulkan minor version
     * @param spirvMajorVersion SPIR-V major version
     * @param spirvMinorVersion SPIR-V minor version
     * @throws IllegalArgumentException if any major version is non-positive or
     *         any minor version is negative
     */
    public VulkanShaderTarget {
        if (majorVersion <= 0) throw new IllegalArgumentException("majorVersion must be positive");
        if (minorVersion < 0) throw new IllegalArgumentException("minorVersion must be non-negative");
        if (spirvMajorVersion <= 0) throw new IllegalArgumentException("spirvMajorVersion must be positive");
        if (spirvMinorVersion < 0) throw new IllegalArgumentException("spirvMinorVersion must be non-negative");
    }

    /**
     * Accepts SPIR-V code.
     *
     * @param code shader code to test
     * @return {@code true} exactly for {@link SpirvShaderCode}
     * @throws NullPointerException if {@code code} is {@code null}
     */
    @Override
    public boolean accepts(ShaderCode code) {
        Objects.requireNonNull(code, "code");
        return code instanceof SpirvShaderCode;
    }
}
