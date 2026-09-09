package io.github.antonschnfeld.drakon.graphics.backend;

/**
 * Snapshot of optional features supported by a {@link GraphicsDevice}.
 *
 * @param compute whether compute shaders and compute dispatch are supported
 * @param indirectDraw whether indexed indirect drawing is supported
 * @param storageBuffers whether shader storage buffers are supported
 */
public record GraphicsCapabilities(
        boolean compute,
        boolean indirectDraw,
        boolean storageBuffers
) {}
