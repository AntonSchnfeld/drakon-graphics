package io.github.antonschnfeld.drakon.graphics.shader;

/**
 * Marker for a concrete representation of shader code.
 *
 * <p>This type deliberately describes representation, not compilation state.
 * A value may contain handwritten source, cross-compiled source, or binary code
 * produced by an external compiler. The graphics API only cares whether the
 * selected {@link ShaderTarget} accepts that representation.</p>
 *
 * <p>Additional representations such as DXIL may be introduced by future
 * modules without changing the meaning of existing shader descriptors.</p>
 */
public interface ShaderCode {
}
