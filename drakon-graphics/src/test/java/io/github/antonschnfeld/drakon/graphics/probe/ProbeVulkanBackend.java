package io.github.antonschnfeld.drakon.graphics.probe;

import io.github.antonschnfeld.drakon.graphics.shader.VulkanShaderTarget;

public final class ProbeVulkanBackend extends AbstractProbeBackend {
    public ProbeVulkanBackend() {
        super("vulkan", "Vulkan probe", new VulkanShaderTarget(1, 3, 1, 6));
    }
}
