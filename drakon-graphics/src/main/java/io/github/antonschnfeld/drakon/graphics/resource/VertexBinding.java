package io.github.antonschnfeld.drakon.graphics.resource;

import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;

import java.util.Objects;

/**
 * One vertex-buffer binding declaration.
 *
 * @param binding non-negative binding index referenced by vertex attributes and
 *        {@link CommandEncoder#setVertexBuffer(int, Buffer, long)}
 * @param stride positive byte stride between consecutive vertex/instance records
 * @param inputRate whether the binding advances per vertex or per instance
 */
public record VertexBinding(int binding, int stride, VertexInputRate inputRate) {
    /**
     * Validates a vertex-buffer binding.
     *
     * @param binding non-negative binding index
     * @param stride positive byte stride
     * @param inputRate input advancement rate
     * @throws IllegalArgumentException if binding is negative or stride non-positive
     * @throws NullPointerException if {@code inputRate} is null
     */
    public VertexBinding {
        if (binding < 0) {
            throw new IllegalArgumentException("binding must be >= 0");
        }
        if (stride <= 0) {
            throw new IllegalArgumentException("stride must be > 0");
        }
        Objects.requireNonNull(inputRate, "inputRate");
    }
}
