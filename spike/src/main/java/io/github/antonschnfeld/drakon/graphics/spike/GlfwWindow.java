package io.github.antonschnfeld.drakon.graphics.spike;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;

/** Spike-owned native window. */
final class GlfwWindow implements AutoCloseable {
    private final long handle;
    private boolean closed;

    GlfwWindow(long handle) {
        this.handle = handle;
    }

    long handle() {
        requireOpen();
        return handle;
    }

    void makeContextCurrent() {
        requireOpen();
        glfwMakeContextCurrent(handle);
    }

    void enableSwapInterval() {
        requireOpen();
        glfwSwapInterval(1);
    }

    boolean shouldClose() {
        requireOpen();
        return glfwWindowShouldClose(handle);
    }

    void pollEvents() {
        requireOpen();
        glfwPollEvents();
    }

    void waitEvents(double timeoutSeconds) {
        requireOpen();
        if (!(timeoutSeconds > 0.0)) {
            throw new IllegalArgumentException("event wait timeout must be positive");
        }
        glfwWaitEventsTimeout(timeoutSeconds);
    }

    void setWindowSize(int width, int height) {
        requireOpen();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window dimensions must be positive");
        }
        glfwSetWindowSize(handle, width, height);
    }

    void iconify() {
        requireOpen();
        glfwIconifyWindow(handle);
    }

    void restore() {
        requireOpen();
        glfwRestoreWindow(handle);
    }

    boolean isIconified() {
        requireOpen();
        return glfwGetWindowAttrib(handle, GLFW_ICONIFIED) == GLFW_TRUE;
    }

    int framebufferWidth() {
        return Math.max(rawFramebufferWidth(), 1);
    }

    int framebufferHeight() {
        return Math.max(rawFramebufferHeight(), 1);
    }

    int rawFramebufferWidth() {
        return framebufferExtent(true);
    }

    int rawFramebufferHeight() {
        return framebufferExtent(false);
    }

    private int framebufferExtent(boolean widthResult) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            IntBuffer width = arena.allocate(ValueLayout.JAVA_INT).asByteBuffer()
                    .order(ByteOrder.nativeOrder()).asIntBuffer();
            IntBuffer height = arena.allocate(ValueLayout.JAVA_INT).asByteBuffer()
                    .order(ByteOrder.nativeOrder()).asIntBuffer();
            glfwGetFramebufferSize(handle, width, height);
            return widthResult ? width.get(0) : height.get(0);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GLFW window is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        glfwDestroyWindow(handle);
    }
}
