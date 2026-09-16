package io.github.antonschnfeld.drakon.graphics.opengl;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

/** Adapts caller-selected byte ranges to native memory for synchronous OpenGL uploads. */
final class OpenGLUploadMemory {
    private OpenGLUploadMemory() {}

    static void withNativeBuffer(ByteBuffer source, Consumer<ByteBuffer> upload) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(upload, "upload");
        ByteBuffer selected = source.slice();
        if (selected.isDirect()) {
            upload.accept(selected);
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment storage = arena.allocate(selected.remaining(), Byte.BYTES);
            storage.copyFrom(MemorySegment.ofBuffer(selected));
            upload.accept(storage.asByteBuffer());
        }
    }
}
