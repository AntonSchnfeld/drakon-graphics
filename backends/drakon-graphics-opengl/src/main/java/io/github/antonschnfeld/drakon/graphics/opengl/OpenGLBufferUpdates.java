package io.github.antonschnfeld.drakon.graphics.opengl;

import java.nio.ByteBuffer;

/** Portable buffer-write validation and recording-time snapshots for OpenGL. */
final class OpenGLBufferUpdates {
    private OpenGLBufferUpdates() {}

    static byte[] snapshot(long bufferSize, long offset, ByteBuffer data) {
        int byteCount = data.remaining();
        validateRange(bufferSize, offset, byteCount);
        byte[] snapshot = new byte[byteCount];
        data.duplicate().get(snapshot);
        return snapshot;
    }

    static void validateRange(long bufferSize, long offset, int byteCount) {
        if (offset < 0) throw new IllegalArgumentException("buffer write offset must be non-negative");
        if (byteCount == 0) throw new IllegalArgumentException("buffer write must not be empty");
        if ((offset & 3L) != 0L) throw new IllegalArgumentException("buffer write offset must be four-byte aligned");
        if ((byteCount & 3) != 0) throw new IllegalArgumentException("buffer write size must be four-byte aligned");
        if (offset > bufferSize - byteCount) throw new IllegalArgumentException("buffer write exceeds destination bounds");
    }
}
