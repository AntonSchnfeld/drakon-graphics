package io.github.antonschnfeld.drakon.graphics.vulkan;

/** Validation and native-command chunk planning for Vulkan buffer updates. */
final class VulkanBufferUpdates {
    static final int MAX_UPDATE_BYTES = 65_536;

    private VulkanBufferUpdates() {}

    static void validateRange(long bufferSize, long offset, long byteCount) {
        if (offset < 0) throw new IllegalArgumentException("buffer write offset must be non-negative");
        if (byteCount == 0) throw new IllegalArgumentException("buffer write must not be empty");
        if ((offset & 3L) != 0L) throw new IllegalArgumentException("buffer write offset must be four-byte aligned");
        if ((byteCount & 3L) != 0L) throw new IllegalArgumentException("buffer write size must be four-byte aligned");
        if (offset > bufferSize - byteCount) throw new IllegalArgumentException("buffer write exceeds destination bounds");
    }

    static void forEachChunk(long offset, long byteCount, ChunkConsumer consumer) {
        long currentOffset = offset;
        long remaining = byteCount;
        while (remaining != 0L) {
            int size = (int) Math.min(remaining, MAX_UPDATE_BYTES);
            consumer.accept(currentOffset, size);
            currentOffset = Math.addExact(currentOffset, size);
            remaining -= size;
        }
    }

    @FunctionalInterface
    interface ChunkConsumer {
        void accept(long offset, int size);
    }
}
