package io.github.antonschnfeld.drakon.graphics.spike;

import org.lwjgl.glfw.GLFWErrorCallback;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;

/** Spike-owned GLFW process lifecycle and window factory. */
final class GlfwPlatform implements AutoCloseable {
    private final GLFWErrorCallback errorCallback;
    private boolean closed;

    GlfwPlatform() {
        errorCallback = GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            clearErrorCallback();
            errorCallback.free();
            throw new IllegalStateException("GLFW initialization failed");
        }
    }

    GlfwWindow createOpenGLWindow(int width, int height, String title) {
        requireOpen();
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        return createWindow(width, height, title);
    }

    GlfwWindow createVulkanWindow(int width, int height, String title) {
        requireOpen();
        if (!glfwVulkanSupported()) throw new IllegalStateException("GLFW reports Vulkan is unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        return createWindow(width, height, title);
    }

    private static GlfwWindow createWindow(int width, int height, String title) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("window dimensions must be positive");
        long handle = glfwCreateWindow(width, height, title, 0L, 0L);
        if (handle == 0L) throw new IllegalStateException("GLFW window creation failed");
        return new GlfwWindow(handle);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GLFW platform is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        glfwTerminate();
        clearErrorCallback();
        errorCallback.free();
    }

    private static void clearErrorCallback() {
        // Use the raw LWJGL entry point so clearing does not resolve the old callback
        // back into Java while its registration may already be invalid.
        nglfwSetErrorCallback(0L);
    }
}
