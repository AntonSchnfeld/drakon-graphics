package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.ResourceState;
import io.github.antonschnfeld.drakon.graphics.resource.Texture;
import io.github.antonschnfeld.drakon.graphics.resource.TextureDescriptor;
import io.github.antonschnfeld.drakon.graphics.resource.TextureFormat;
import io.github.antonschnfeld.drakon.graphics.resource.TextureUsage;

import java.util.Set;

final class VulkanTexture extends VulkanResource implements Texture {
    final long image;
    final long memory;
    final long view;
    private final TextureDescriptor descriptor;
    ResourceState state = ResourceState.UNDEFINED;

    VulkanTexture(VulkanDevice device, long image, long memory, long view, TextureDescriptor descriptor) {
        super(device);
        this.image = image;
        this.memory = memory;
        this.view = view;
        this.descriptor = descriptor;
    }

    @Override public int width() { requireAlive(); return descriptor.width(); }
    @Override public int height() { requireAlive(); return descriptor.height(); }
    @Override public TextureFormat format() { requireAlive(); return descriptor.format(); }
    @Override public Set<TextureUsage> usage() { requireAlive(); return descriptor.usage(); }
    @Override void deleteNative() { device.destroyTexture(image, view, memory); }
}
