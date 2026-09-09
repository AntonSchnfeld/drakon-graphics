package io.github.antonschnfeld.drakon.graphics.resource;

/**
 * Opaque device shader resource created from backend-targeted shader code.
 *
 * <p>The resource does not expose or encode a binary "compiled" state. Its
 * backend may internally retain source, native bytecode, driver objects, or
 * other representation details as required.</p>
 */
public interface Shader extends GpuResource {
    /**
     * Returns the execution stage represented by this shader.
     *
     * @return shader stage
     */
    ShaderStage stage();
}
