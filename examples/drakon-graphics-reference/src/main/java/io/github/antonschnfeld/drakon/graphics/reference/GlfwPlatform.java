package io.github.antonschnfeld.drakon.graphics.reference;

import org.lwjgl.glfw.GLFWErrorCallback;

import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_API;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_DEBUG_CONTEXT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.nglfwSetErrorCallback;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;

/** Example-owned GLFW process lifecycle and window factory. */
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
        if (!glfwVulkanSupported()) {
            throw new IllegalStateException("GLFW reports Vulkan is unavailable");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        return createWindow(width, height, title);
    }

    private static GlfwWindow createWindow(int width, int height, String title) {
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
        nglfwSetErrorCallback(0L);
    }
}
