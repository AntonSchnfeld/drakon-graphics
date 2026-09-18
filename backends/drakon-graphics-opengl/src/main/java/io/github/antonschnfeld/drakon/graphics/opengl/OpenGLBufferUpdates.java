package io.github.antonschnfeld.drakon.graphics.opengl;

import java.lang.foreign.MemorySegment;

/** Portable buffer-write validation and recording-time snapshots for OpenGL. */
final class OpenGLBufferUpdates {
    private OpenGLBufferUpdates() {}

    static MemorySegment snapshot(
            OpenGLCommandMemory memory,
            long bufferSize,
            long offset,
            MemorySegment data) {
        long byteCount = data.byteSize();
        validateRange(bufferSize, offset, byteCount);
        return memory.snapshot(data);
    }

    static void validateRange(long bufferSize, long offset, long byteCount) {
        if (offset < 0) throw new IllegalArgumentException("buffer write offset must be non-negative");
        if (byteCount == 0) throw new IllegalArgumentException("buffer write must not be empty");
        if ((offset & 3L) != 0L) throw new IllegalArgumentException("buffer write offset must be four-byte aligned");
        if ((byteCount & 3L) != 0L) throw new IllegalArgumentException("buffer write size must be four-byte aligned");
        if (offset > bufferSize - byteCount) throw new IllegalArgumentException("buffer write exceeds destination bounds");
    }
}
