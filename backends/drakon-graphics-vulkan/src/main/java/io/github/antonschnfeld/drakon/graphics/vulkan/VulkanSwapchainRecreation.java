package io.github.antonschnfeld.drakon.graphics.vulkan;

/** Explicit pending/completed state for one presentation target's recreations. */
final class VulkanSwapchainRecreation {
    private boolean pending;
    private int completedCount;

    boolean pending() {
        return pending;
    }

    int completedCount() {
        return completedCount;
    }

    void request() {
        pending = true;
    }

    void complete() {
        if (!pending) throw new IllegalStateException("no swapchain recreation is pending");
        pending = false;
        completedCount++;
    }
}
