package io.github.antonschnfeld.drakon.graphics.shader;

/**
 * Backend shader compilation target exposed by a graphics device.
 *
 * <p>The target is intended primarily for external shader-toolchain modules.
 * Such a module may inspect the concrete target type to choose compiler flags,
 * output format, language version, and execution environment before producing a
 * {@link ShaderDescriptor} for the device.</p>
 *
 * <p>{@link #accepts(ShaderCode)} only answers whether the representation is
 * supported by this target. It does not validate the contents of the code.</p>
 */
public interface ShaderTarget {
    /**
     * Reports whether this target accepts the supplied code representation.
     *
     * @param code shader code to test
     * @return {@code true} when this representation may be passed to a device
     *         exposing this target
     * @throws NullPointerException if {@code code} is {@code null}
     */
    boolean accepts(ShaderCode code);
}
