package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;

/**
 * Byte range of a buffer bound to a shader resource slot.
 *
 * @param buffer source buffer
 * @param offset non-negative byte offset into the buffer
 * @param size positive byte length of the bound range
 */
public record BufferBinding(Buffer buffer, long offset, long size) {
    /**
     * Validates a buffer range.
     *
     * @param buffer buffer being sliced
     * @param offset non-negative byte offset
     * @param size positive byte count
     * @throws NullPointerException if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if the range is empty, negative, or
     *         extends beyond the buffer
     */
    public BufferBinding {
        Objects.requireNonNull(buffer, "buffer");
        if (offset < 0 || size <= 0 || offset > buffer.size() - size) {
            throw new IllegalArgumentException("invalid buffer binding range");
        }
    }

    /**
     * Creates a binding spanning the complete buffer.
     *
     * @param buffer buffer to bind
     * @return whole-buffer range
     * @throws NullPointerException if {@code buffer} is null
     */
    public static BufferBinding whole(Buffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        return new BufferBinding(buffer, 0, buffer.size());
    }
}
