package io.github.antonschnfeld.drakon.graphics.spike;

import io.github.antonschnfeld.drakon.graphics.vulkan.VulkanSurfaceFactory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
            // Borrowed from GLFW; GLFW owns the NUL-terminated extension-name storage.
            names.add(MemoryUtil.memUTF8(extensions.get(i)));
        }
        return List.copyOf(names);
    }

    @Override
    public long createSurface(long instanceHandle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment surface = arena.allocate(ValueLayout.JAVA_LONG);
            int result = nglfwCreateWindowSurface(instanceHandle, window.handle(), 0L, surface.address());
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("glfwCreateWindowSurface failed with VkResult " + result);
            }
            return surface.get(ValueLayout.JAVA_LONG, 0);
        }
    }

    @Override public int width() { return window.framebufferWidth(); }
    @Override public int height() { return window.framebufferHeight(); }
}
