package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.Shader;
import io.github.antonschnfeld.drakon.graphics.resource.ShaderStage;

final class VulkanShader extends VulkanResource implements Shader {
    final long module;
    final ShaderStage stage;
    final String entryPoint;

    VulkanShader(VulkanDevice device, long module, ShaderStage stage, String entryPoint) {
        super(device);
        this.module = module;
        this.stage = stage;
        this.entryPoint = entryPoint;
    }

    @Override public ShaderStage stage() { requireAlive(); return stage; }
    @Override void deleteNative() { device.destroyShaderModule(module); }
}
