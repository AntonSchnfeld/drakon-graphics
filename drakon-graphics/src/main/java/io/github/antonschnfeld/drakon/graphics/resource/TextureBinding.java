package io.github.antonschnfeld.drakon.graphics.resource;

import java.util.Objects;

/**
 * Texture and sampler pair supplied to one sampled-texture {@link Binding}.
 *
 * <p>The graphics API models the pair as one logical binding because that is
 * portable across both Vulkan combined image-sampler descriptors and OpenGL
 * texture units. Neither resource is owned by this value.</p>
 *
 * @param texture sampled texture
 * @param sampler sampler state used for the texture
 */
public record TextureBinding(Texture texture, Sampler sampler) {
    /**
     * Validates the texture/sampler pair.
     *
     * @param texture sampled texture
     * @param sampler sampler state
     * @throws NullPointerException if either resource is {@code null}
     * @throws IllegalArgumentException if the texture lacks
     *         {@link TextureUsage#SAMPLED} usage
     */
    public TextureBinding {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(sampler, "sampler");
        if (!texture.usage().contains(TextureUsage.SAMPLED)) {
            throw new IllegalArgumentException("sampled texture lacks SAMPLED usage");
        }
    }
}
