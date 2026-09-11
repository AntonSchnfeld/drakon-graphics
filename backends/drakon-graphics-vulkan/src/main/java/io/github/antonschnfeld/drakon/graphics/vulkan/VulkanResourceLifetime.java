package io.github.antonschnfeld.drakon.graphics.vulkan;

final class VulkanResourceLifetime {
    private boolean logicalClosed;
    private boolean nativeDestroyed;
    private int inFlightReferences;

    synchronized boolean isLogicalClosed() {
        return logicalClosed;
    }

    synchronized void requireLogicallyOpen() {
        if (logicalClosed) throw new IllegalStateException("resource is closed");
    }

    synchronized boolean closeAndClaimNativeDestruction() {
        if (logicalClosed) return false;
        logicalClosed = true;
        return claimNativeDestructionIfReady();
    }

    synchronized void retainForSubmission() {
        requireLogicallyOpen();
        if (nativeDestroyed) throw new IllegalStateException("resource native state is destroyed");
        inFlightReferences = Math.incrementExact(inFlightReferences);
    }

    synchronized boolean releaseAndClaimNativeDestruction() {
        if (inFlightReferences == 0) throw new IllegalStateException("resource has no in-flight reference");
        inFlightReferences--;
        return claimNativeDestructionIfReady();
    }

    synchronized boolean closeForDeviceAndClaimNativeDestruction() {
        logicalClosed = true;
        inFlightReferences = 0;
        if (nativeDestroyed) return false;
        nativeDestroyed = true;
        return true;
    }

    synchronized int inFlightReferences() {
        return inFlightReferences;
    }

    synchronized boolean isNativeDestroyed() {
        return nativeDestroyed;
    }

    private boolean claimNativeDestructionIfReady() {
        if (!logicalClosed || inFlightReferences != 0 || nativeDestroyed) return false;
        nativeDestroyed = true;
        return true;
    }
}
