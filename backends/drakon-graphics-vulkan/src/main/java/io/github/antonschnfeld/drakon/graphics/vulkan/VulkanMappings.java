package io.github.antonschnfeld.drakon.graphics.vulkan;

import io.github.antonschnfeld.drakon.graphics.resource.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

final class VulkanMappings {
    private VulkanMappings() {}

    static int format(TextureFormat format) {
        return switch (format) {
            case RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case BGRA8_UNORM -> VK_FORMAT_B8G8R8A8_UNORM;
            case D32_FLOAT -> VK_FORMAT_D32_SFLOAT;
        };
    }


    static int filter(SamplerDescriptor.Filter filter) {
        return switch (filter) {
            case NEAREST -> VK_FILTER_NEAREST;
            case LINEAR -> VK_FILTER_LINEAR;
        };
    }

    static int addressMode(SamplerDescriptor.AddressMode mode) {
        return switch (mode) {
            case REPEAT -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case CLAMP_TO_EDGE -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        };
    }

    static int vertexFormat(VertexFormat format) {
        return switch (format) {
            case FLOAT2 -> VK_FORMAT_R32G32_SFLOAT;
            case FLOAT3 -> VK_FORMAT_R32G32B32_SFLOAT;
            case FLOAT4 -> VK_FORMAT_R32G32B32A32_SFLOAT;
        };
    }

    static int topology(PrimitiveTopology topology) {
        return switch (topology) {
            case TRIANGLES -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
        };
    }

    static int indexType(IndexType type) {
        return switch (type) {
            case UINT16 -> VK_INDEX_TYPE_UINT16;
            case UINT32 -> VK_INDEX_TYPE_UINT32;
        };
    }

    static int compare(CompareOp op) {
        return switch (op) {
            case NEVER -> VK_COMPARE_OP_NEVER;
            case LESS -> VK_COMPARE_OP_LESS;
            case LESS_OR_EQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case EQUAL -> VK_COMPARE_OP_EQUAL;
            case GREATER_OR_EQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GREATER -> VK_COMPARE_OP_GREATER;
            case ALWAYS -> VK_COMPARE_OP_ALWAYS;
        };
    }

    static int cull(CullMode mode) {
        return switch (mode) {
            case NONE -> VK_CULL_MODE_NONE;
            case FRONT -> VK_CULL_MODE_FRONT_BIT;
            case BACK -> VK_CULL_MODE_BACK_BIT;
        };
    }

    static int shaderStage(ShaderStage stage) {
        return switch (stage) {
            case VERTEX -> VK_SHADER_STAGE_VERTEX_BIT;
            case FRAGMENT -> VK_SHADER_STAGE_FRAGMENT_BIT;
        };
    }

    static int shaderStages(java.util.Set<ShaderStage> stages) {
        int flags = 0;
        for (ShaderStage stage : stages) flags |= shaderStage(stage);
        return flags;
    }

    static int descriptorType(BindingType type) {
        return switch (type) {
            case SAMPLED_TEXTURE -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        };
    }

    static int bufferUsage(java.util.Set<BufferUsage> usages) {
        int flags = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        for (BufferUsage usage : usages) {
            flags |= switch (usage) {
                case VERTEX -> VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
                case INDEX -> VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
                case UNIFORM -> VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
            };
        }
        return flags;
    }

    static int imageUsage(java.util.Set<TextureUsage> usages) {
        int flags = 0;
        for (TextureUsage usage : usages) {
            flags |= switch (usage) {
                case COLOR_ATTACHMENT -> VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
                case DEPTH_ATTACHMENT -> VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
                case SAMPLED -> VK_IMAGE_USAGE_SAMPLED_BIT;
                case COPY_SRC -> VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
                case COPY_DST -> VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            };
        }
        return flags;
    }

    static int imageAspect(TextureFormat format) {
        return format.isDepth() ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
    }

    static int imageLayout(ResourceState state) {
        return switch (state) {
            case UNDEFINED -> VK_IMAGE_LAYOUT_UNDEFINED;
            case COLOR_ATTACHMENT_WRITE -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            case DEPTH_ATTACHMENT_WRITE -> VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
            case SAMPLED_READ -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            case COPY_SRC -> VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            case COPY_DST -> VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            default -> throw new IllegalArgumentException(state + " is not a texture state");
        };
    }

    /**
     * Maps the portable state to a synchronization2 stage mask. Read-only shader
     * states intentionally use ALL_COMMANDS because ResourceState does not encode
     * the consuming shader stages. That is correct but potentially conservative,
     * and is one of the explicit questions this spike is measuring.
     */
    static long stageMask(ResourceState state) {
        return switch (state) {
            case UNDEFINED -> VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
            case COLOR_ATTACHMENT_WRITE -> VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
            case DEPTH_ATTACHMENT_WRITE -> VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
            case VERTEX_READ -> VK_PIPELINE_STAGE_2_VERTEX_ATTRIBUTE_INPUT_BIT;
            case INDEX_READ -> VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT;
            case COPY_SRC, COPY_DST -> VK_PIPELINE_STAGE_2_TRANSFER_BIT;
            case SAMPLED_READ, UNIFORM_READ -> VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        };
    }

    static long accessMask(ResourceState state) {
        return switch (state) {
            case UNDEFINED -> 0L;
            case COLOR_ATTACHMENT_WRITE -> VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
            case DEPTH_ATTACHMENT_WRITE -> VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case SAMPLED_READ -> VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
            case UNIFORM_READ -> VK_ACCESS_2_UNIFORM_READ_BIT;
            case VERTEX_READ -> VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT;
            case INDEX_READ -> VK_ACCESS_2_INDEX_READ_BIT;
            case COPY_SRC -> VK_ACCESS_2_TRANSFER_READ_BIT;
            case COPY_DST -> VK_ACCESS_2_TRANSFER_WRITE_BIT;
        };
    }
}
