package io.github.antonschnfeld.drakon.graphics.spike;

import org.lwjgl.system.MemoryStack;

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

    void disableSwapInterval() {
        requireOpen();
        glfwSwapInterval(0);
    }

    boolean shouldClose() {
        requireOpen();
        return glfwWindowShouldClose(handle);
    }

    void pollEvents() {
        requireOpen();
        glfwPollEvents();
    }

    int framebufferWidth() {
        return framebufferExtent(true);
    }

    int framebufferHeight() {
        return framebufferExtent(false);
    }

    private int framebufferExtent(boolean widthResult) {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, width, height);
            return Math.max(widthResult ? width.get(0) : height.get(0), 1);
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
