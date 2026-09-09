package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.resource.*;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;

final class OpenGLMappings {
    private OpenGLMappings() {}

    static int textureInternalFormat(TextureFormat format) {
        return switch (format) {
            case RGBA8_UNORM, BGRA8_UNORM -> GL_RGBA8;
            case RGBA16_FLOAT -> GL_RGBA16F;
            case D32_FLOAT -> GL_DEPTH_COMPONENT32F;
        };
    }

    static int textureExternalFormat(TextureFormat format) {
        return format.isDepth() ? GL_DEPTH_COMPONENT : GL_RGBA;
    }

    static int textureExternalType(TextureFormat format) {
        return switch (format) {
            case RGBA8_UNORM, BGRA8_UNORM -> GL_UNSIGNED_BYTE;
            case RGBA16_FLOAT -> GL_HALF_FLOAT;
            case D32_FLOAT -> GL_FLOAT;
        };
    }

    static int primitive(PrimitiveTopology topology) {
        return switch (topology) {
            case TRIANGLES -> GL_TRIANGLES;
            case LINES -> GL_LINES;
            case POINTS -> GL_POINTS;
        };
    }

    static int indexType(IndexType type) {
        return switch (type) {
            case UINT16 -> GL_UNSIGNED_SHORT;
            case UINT32 -> GL_UNSIGNED_INT;
        };
    }

    static int compare(CompareOp op) {
        return switch (op) {
            case NEVER -> GL_NEVER;
            case LESS -> GL_LESS;
            case LESS_OR_EQUAL -> GL_LEQUAL;
            case EQUAL -> GL_EQUAL;
            case GREATER_OR_EQUAL -> GL_GEQUAL;
            case GREATER -> GL_GREATER;
            case ALWAYS -> GL_ALWAYS;
        };
    }

    static int cull(CullMode mode) {
        return switch (mode) {
            case FRONT -> GL_FRONT;
            case BACK -> GL_BACK;
            case NONE -> 0;
        };
    }

    static int bufferTarget(BindingType type) {
        return switch (type) {
            case UNIFORM_BUFFER -> GL_UNIFORM_BUFFER;
            case STORAGE_BUFFER -> GL_SHADER_STORAGE_BUFFER;
            case SAMPLED_TEXTURE -> throw new IllegalArgumentException("sampled textures are not buffer bindings");
        };
    }

    static int maxBindingCount(BindingType type) {
        return switch (type) {
            case SAMPLED_TEXTURE -> org.lwjgl.opengl.GL11C.glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            case UNIFORM_BUFFER -> org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL31C.GL_MAX_UNIFORM_BUFFER_BINDINGS);
            case STORAGE_BUFFER -> org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        };
    }
}
