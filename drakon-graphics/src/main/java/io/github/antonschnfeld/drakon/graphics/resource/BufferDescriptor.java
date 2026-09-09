package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable buffer creation descriptor.
 *
 * @param size positive allocation size in bytes
 * @param usage non-empty set of all usages required during the buffer lifetime
 */
public record BufferDescriptor(long size, Set<BufferUsage> usage) {
    /**
     * Validates and defensively copies a buffer descriptor.
     *
     * @param size positive size in bytes
     * @param usage non-null, non-empty usage set
     * @throws IllegalArgumentException if {@code size <= 0} or usage is empty
     * @throws NullPointerException if {@code usage} or an element is null
     */
    public BufferDescriptor {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        usage = Set.copyOf(Objects.requireNonNull(usage, "usage"));
        if (usage.isEmpty()) {
            throw new IllegalArgumentException("usage must not be empty");
        }
    }
}
