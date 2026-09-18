package io.github.antonschnfeld.drakon.graphics.opengl;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

/** Adapts caller-selected byte ranges to native memory for synchronous OpenGL uploads. */
final class OpenGLUploadMemory {
    private OpenGLUploadMemory() {}

    static void withNativeBuffer(MemorySegment source, Consumer<ByteBuffer> upload) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(upload, "upload");
        if (source.byteSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("OpenGL upload exceeds the LWJGL ByteBuffer limit");
        }
        if (source.isNative()) {
            upload.accept(source.asByteBuffer());
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment storage = arena.allocate(source.byteSize(), Byte.BYTES);
            storage.copyFrom(source);
            upload.accept(storage.asByteBuffer());
        }
    }
}
