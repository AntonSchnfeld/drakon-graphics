package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import org.lwjgl.vulkan.VK;

import java.util.Objects;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;

/** LWJGL Vulkan 1.3 backend service provider used by the backend spike. */
public final class VulkanBackend implements GraphicsBackend {
    /** Creates a stateless Vulkan backend provider. */
    public VulkanBackend() {}

    @Override public String id() { return "vulkan"; }

    @Override
    public boolean isSupported() {
        try {
            int version = VK.getInstanceVersionSupported();
            return VK_API_VERSION_MAJOR(version) > 1
                    || (VK_API_VERSION_MAJOR(version) == 1 && VK_API_VERSION_MINOR(version) >= 3);
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Creates a headless/offscreen Vulkan 1.3 device.
     *
     * <p>Window-surface instance extensions and swapchain suitability depend on
     * the actual window system, so presentation bootstrap is intentionally a
     * backend-specific operation rather than part of this generic method.</p>
     */
    @Override
    public GraphicsDevice createDevice(GraphicsDeviceConfig config) {
        return VulkanDevice.createHeadless(Objects.requireNonNull(config, "config"));
    }

    /**
     * Creates a visible GLFW/Vulkan device whose swapchain is represented by a
     * stable presentation-backed RenderTarget. This bootstrap is deliberately
     * backend-specific; window creation is not part of drakon-graphics core.
     *
     * @param config device options
     * @param width positive initial window width
     * @param height positive initial window height
     * @param title non-null window title
     * @return windowed Vulkan device
     */
    public VulkanDevice createWindowedDevice(
            GraphicsDeviceConfig config,
            int width,
            int height,
            String title) {
        return VulkanDevice.createWindowed(Objects.requireNonNull(config, "config"), width, height, title);
    }
}

