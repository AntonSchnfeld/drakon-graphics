package io.github.antonschnfeld.drakon.graphics.reference;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwWaitEvents;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;

/** Example-owned GLFW window. */
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

    void waitEvents() {
        requireOpen();
        glfwWaitEvents();
    }

    int rawFramebufferWidth() {
        return framebufferExtent(true);
    }

    int rawFramebufferHeight() {
        return framebufferExtent(false);
    }

    int renderableFramebufferWidth() {
        return Math.max(rawFramebufferWidth(), 1);
    }

    int renderableFramebufferHeight() {
        return Math.max(rawFramebufferHeight(), 1);
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
