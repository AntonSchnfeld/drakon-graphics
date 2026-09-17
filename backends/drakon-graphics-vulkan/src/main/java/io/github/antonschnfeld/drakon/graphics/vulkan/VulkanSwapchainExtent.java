package io.github.antonschnfeld.drakon.graphics.vulkan;

import java.util.function.IntSupplier;

/** Hardware-free selection of a recreatable Vulkan swapchain extent. */
final class VulkanSwapchainExtent {
    static final int VARIABLE = 0xFFFFFFFF;

    private VulkanSwapchainExtent() {}

    static Plan plan(
            int currentWidth,
            int currentHeight,
            IntSupplier surfaceWidth,
            IntSupplier surfaceHeight,
            int minWidth,
            int minHeight,
            int maxWidth,
            int maxHeight) {
        boolean variableWidth = currentWidth == VARIABLE;
        boolean variableHeight = currentHeight == VARIABLE;
        if (variableWidth != variableHeight) {
            throw new IllegalArgumentException("surface current extent must be entirely fixed or variable");
        }
        if (!variableWidth) {
            return currentWidth == 0 || currentHeight == 0
                    ? Plan.deferred()
                    : Plan.recreatable(currentWidth, currentHeight);
        }

        int requestedWidth = surfaceWidth.getAsInt();
        int requestedHeight = surfaceHeight.getAsInt();
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalStateException("surface factory returned a non-positive renderable extent");
        }
        return Plan.recreatable(
                clamp(requestedWidth, minWidth, maxWidth),
                clamp(requestedHeight, minHeight, maxHeight));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Result of inspecting the current surface extent. */
    record Plan(boolean recreatable, int width, int height) {
        private static Plan deferred() {
            return new Plan(false, 0, 0);
        }

        private static Plan recreatable(int width, int height) {
            return new Plan(true, width, height);
        }
    }
}
