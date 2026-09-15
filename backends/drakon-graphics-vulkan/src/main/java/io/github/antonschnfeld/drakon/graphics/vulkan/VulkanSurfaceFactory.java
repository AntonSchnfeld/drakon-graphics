package io.github.antonschnfeld.drakon.graphics.vulkan;

import java.util.List;

/**
 * Minimal platform integration used to bootstrap Vulkan presentation.
 *
 * <p>The backend creates a {@code VkInstance}, then invokes
 * {@link #createSurface(long)} before physical-device and queue selection. A
 * non-zero surface returned successfully transfers to the created backend
 * render target. That target destroys the surface exactly once, before its
 * instance is destroyed. The factory continues to own all platform state and
 * the target never destroys that state.</p>
 *
 * <p>The factory may be retained for live extent queries, so it must remain
 * usable until the target is closed. No LWJGL Vulkan object is exposed through
 * this contract; native values are represented by backend-specific longs.</p>
 */
public interface VulkanSurfaceFactory {
    /**
     * Returns the Vulkan instance-extension names required to create the surface.
     *
     * @return immutable or stable extension-name collection
     */
    List<String> requiredInstanceExtensions();

    /**
     * Creates a surface for the supplied native {@code VkInstance} handle.
     * Ownership of a successful non-zero result transfers to the backend target.
     *
     * @param instanceHandle native {@code VkInstance} handle
     * @return non-zero native {@code VkSurfaceKHR} handle
     */
    long createSurface(long instanceHandle);

    /**
     * Returns the current positive renderable width in pixels.
     *
     * @return current width
     */
    int width();

    /**
     * Returns the current positive renderable height in pixels.
     *
     * @return current height
     */
    int height();
}
