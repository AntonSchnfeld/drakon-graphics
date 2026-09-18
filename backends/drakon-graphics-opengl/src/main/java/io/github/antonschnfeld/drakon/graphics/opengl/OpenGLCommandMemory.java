package io.github.antonschnfeld.drakon.graphics.opengl;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Native snapshots owned by one encoder and then its finished command list. */
final class OpenGLCommandMemory implements AutoCloseable {
    private final Runnable release;
    private final Arena arena = Arena.ofShared();
    private boolean closed;

    OpenGLCommandMemory(OpenGLDevice device) {
        release = () -> device.releaseCommandMemory(this);
    }

    OpenGLCommandMemory() {
        release = () -> { };
    }

    synchronized MemorySegment snapshot(MemorySegment source) {
        if (closed) throw new IllegalStateException("command memory is closed");
        MemorySegment copy = arena.allocate(source.byteSize(), Byte.BYTES);
        copy.copyFrom(source);
        return copy.asReadOnly();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        arena.close();
        release.run();
    }
}
