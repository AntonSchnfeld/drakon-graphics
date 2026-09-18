package io.github.antonschnfeld.drakon.graphics.reference;

import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.shader.GlslShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.OpenGLShaderTarget;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderTarget;
import io.github.antonschnfeld.drakon.graphics.shader.SpirvShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.VulkanShaderTarget;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_3;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan;

/** Loads bundled shader sources and prepares the representation required by a device. */
final class ShaderResources {
    private static final String OPENGL_ROOT = "/shaders/opengl/";
    private static final String VULKAN_ROOT = "/shaders/vulkan/";

    private ShaderResources() {}

    static PreparedShaders prepare(GraphicsDevice device) {
        ShaderTarget target = device.shaderTarget();
        if (target instanceof OpenGLShaderTarget openGL) {
            if (openGL.glslVersion() < 430) {
                throw new IllegalStateException("reference renderer requires GLSL 4.30 or newer");
            }
            return new PreparedShaders(
                    glsl(OPENGL_ROOT + "scene.vert"),
                    glsl(OPENGL_ROOT + "scene.frag"),
                    glsl(OPENGL_ROOT + "post.vert"),
                    glsl(OPENGL_ROOT + "post.frag"));
        }
        if (target instanceof VulkanShaderTarget vulkan) {
            if (vulkan.majorVersion() < 1 || vulkan.minorVersion() < 3) {
                throw new IllegalStateException("reference renderer requires Vulkan 1.3 or newer");
            }
            return new PreparedShaders(
                    compile(VULKAN_ROOT + "scene.vert", shaderc_glsl_vertex_shader),
                    compile(VULKAN_ROOT + "scene.frag", shaderc_glsl_fragment_shader),
                    compile(VULKAN_ROOT + "post.vert", shaderc_glsl_vertex_shader),
                    compile(VULKAN_ROOT + "post.frag", shaderc_glsl_fragment_shader));
        }
        throw new IllegalArgumentException("unsupported shader target: " + target);
    }

    private static GlslShaderCode glsl(String path) {
        return new GlslShaderCode(read(path));
    }

    private static SpirvShaderCode compile(String path, int kind) {
        String source = read(path);
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new IllegalStateException("shaderc compiler creation failed");
        long options = shaderc_compile_options_initialize();
        if (options == 0L) {
            shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc options creation failed");
        }
        shaderc_compile_options_set_target_env(
                options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
        try {
            try (Arena arena = Arena.ofConfined()) {
                var sourceText = arena.allocateFrom(source);
                long result = shaderc_compile_into_spv(
                        compiler,
                        sourceText.asSlice(0, sourceText.byteSize() - 1).asByteBuffer(),
                        kind,
                        arena.allocateFrom(path).asByteBuffer(),
                        arena.allocateFrom("main").asByteBuffer(),
                        options);
                try {
                    if (result == 0L) throw new IllegalStateException("shaderc compilation failed");
                    if (shaderc_result_get_compilation_status(result)
                            != shaderc_compilation_status_success) {
                        throw new IllegalArgumentException(
                                "shaderc failed for " + path + ": "
                                        + shaderc_result_get_error_message(result));
                    }
                    ByteBuffer bytes = shaderc_result_get_bytes(result);
                    if (bytes == null) throw new IllegalStateException("shaderc returned no SPIR-V bytes");
                    return new SpirvShaderCode(bytes);
                } finally {
                    if (result != 0L) shaderc_result_release(result);
                }
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private static String read(String path) {
        try (InputStream stream = ShaderResources.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("missing shader resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read shader resource: " + path, failure);
        }
    }

    record PreparedShaders(
            ShaderCode sceneVertex,
            ShaderCode sceneFragment,
            ShaderCode postVertex,
            ShaderCode postFragment) {}
}
