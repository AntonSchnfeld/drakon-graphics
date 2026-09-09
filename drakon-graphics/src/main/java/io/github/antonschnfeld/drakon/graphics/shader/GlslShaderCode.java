package io.github.antonschnfeld.drakon.graphics.shader;

import java.util.Objects;

/**
 * GLSL shader source code.
 *
 * <p>The record does not claim that the source is syntactically valid or that
 * it targets any particular OpenGL version. That validation belongs to the
 * compiler that produced the code and ultimately to the device consuming it.</p>
 *
 * @param code non-null GLSL source text
 */
public record GlslShaderCode(String code) implements ShaderCode {
    /**
     * Creates GLSL code.
     *
     * @param code shader source text
     * @throws NullPointerException if {@code code} is {@code null}
     */
    public GlslShaderCode {
        Objects.requireNonNull(code, "code");
    }
}
