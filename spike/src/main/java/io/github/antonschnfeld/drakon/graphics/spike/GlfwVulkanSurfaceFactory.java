package io.github.antonschnfeld.drakon.graphics.spike;

import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanSurfaceFactory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.glfw.GLFWVulkan.nglfwCreateWindowSurface;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/** GLFW implementation of the backend-specific Vulkan surface bootstrap. */
final class GlfwVulkanSurfaceFactory implements VulkanSurfaceFactory {
    private final GlfwWindow window;

    GlfwVulkanSurfaceFactory(GlfwWindow window) {
        this.window = window;
    }

    @Override
    public List<String> requiredInstanceExtensions() {
        PointerBuffer extensions = glfwGetRequiredInstanceExtensions();
        if (extensions == null) throw new IllegalStateException("GLFW returned no Vulkan instance extensions");
        List<String> names = new ArrayList<>(extensions.remaining());
        for (int i = extensions.position(); i < extensions.limit(); i++) {
            names.add(MemoryUtil.memUTF8(extensions.get(i)));
        }
        return List.copyOf(names);
    }

    @Override
    public long createSurface(long instanceHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer surface = stack.mallocLong(1);
            int result = nglfwCreateWindowSurface(instanceHandle, window.handle(), 0L, MemoryUtil.memAddress(surface));
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("glfwCreateWindowSurface failed with VkResult " + result);
            }
            return surface.get(0);
        }
    }

    @Override public int width() { return window.framebufferWidth(); }
    @Override public int height() { return window.framebufferHeight(); }
}
