package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsBackend;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;

import java.util.Objects;

/** LWJGL Vulkan 1.3 backend service provider. */
public final class VulkanBackend implements GraphicsBackend {
    /** Creates a stateless Vulkan backend provider. */
    public VulkanBackend() {}

    @Override public String id() { return "vulkan"; }

    @Override
    public boolean isSupported() {
        try (VulkanDevice probe = VulkanDevice.createHeadless(GraphicsDeviceConfig.defaults())) {
            probe.requireOpen();
            return true;
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
     * Creates a presentation-capable device using an external surface factory.
     *
     * <p>The factory creates the first surface after instance creation so device
     * and queue selection can verify presentation support. The resulting target
     * owns the surface and Vulkan swapchain resources, while the factory retains
     * ownership of all platform state.</p>
     *
     * @param config device options
     * @param surfaceFactory external surface and extent integration
     * @return the device and its first presentation-compatible render target
     */
    public VulkanPresentation createPresentationDevice(
            GraphicsDeviceConfig config,
            VulkanSurfaceFactory surfaceFactory) {
        return VulkanDevice.createPresentation(
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(surfaceFactory, "surfaceFactory"));
    }
}
