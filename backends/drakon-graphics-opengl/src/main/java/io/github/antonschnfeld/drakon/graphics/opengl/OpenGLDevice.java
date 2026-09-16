package io.github.antonschnfeld.drakon.graphics.opengl;

import io.github.antonschnfeld.drakon.graphics.backend.*;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDevice;
import io.github.antonschnfeld.drakon.graphics.backend.GraphicsDeviceConfig;
import io.github.antonschnfeld.drakon.graphics.command.CommandEncoder;
import io.github.antonschnfeld.drakon.graphics.command.CommandList;
import io.github.antonschnfeld.drakon.graphics.resource.*;
import io.github.antonschnfeld.drakon.graphics.shader.GlslShaderCode;
import io.github.antonschnfeld.drakon.graphics.shader.OpenGLShaderTarget;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderDescriptor;
import io.github.antonschnfeld.drakon.graphics.shader.ShaderTarget;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.*;

/**
 * LWJGL OpenGL 4.3 implementation of {@link GraphicsDevice}.
 *
 * <p>The device uses, but never owns, the OpenGL context that is current during
 * creation. The same external context must be current whenever the device is
 * used. Device close destroys only OpenGL objects created by this device and
 * neither detaches nor destroys that context.</p>
 *
 * <p>Commands are recorded as Java objects and replayed on submission. This is
 * intentionally not the final OpenGL hot-path design; it preserves the portable
 * command-list semantics while making backend mapping and validation observable.</p>
 */
public final class OpenGLDevice implements GraphicsDevice {
    private final GraphicsDeviceConfig config;
    private final GLCapabilities capabilities;
    private final OpenGLNativeDebug nativeDebug;
    private final long uniformBufferOffsetAlignment;
    private final OpenGLShaderTarget shaderTarget = new OpenGLShaderTarget(4, 3, 430);
    private final List<OpenGLResource> resources = new ArrayList<>();
    private boolean closed;

    private OpenGLDevice(
            GraphicsDeviceConfig config,
            GLCapabilities capabilities,
            OpenGLNativeDebug nativeDebug,
            long uniformBufferOffsetAlignment) {
        this.config = config;
        this.capabilities = capabilities;
        this.nativeDebug = nativeDebug;
        this.uniformBufferOffsetAlignment = uniformBufferOffsetAlignment;
    }

    static OpenGLDevice create(GraphicsDeviceConfig config) {
        Objects.requireNonNull(config, "config");
        OpenGLNativeDebug nativeDebug = null;
        try {
            GLCapabilities caps = GL.createCapabilities();
            if (!caps.OpenGL43) {
                throw new IllegalStateException("the current external context does not support OpenGL 4.3");
            }
            nativeDebug = OpenGLNativeDebug.install(config.validation());
            long alignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
            if (alignment <= 0) {
                throw new IllegalStateException("OpenGL reported an invalid uniform-buffer offset alignment");
            }
            return new OpenGLDevice(config, caps, nativeDebug, alignment);
        } catch (RuntimeException | Error failure) {
            if (nativeDebug != null) nativeDebug.close();
            throw new IllegalStateException("an OpenGL 4.3 context must be current when creating a device", failure);
        }
    }

    void requireOpen() {
        if (closed) {
            throw new IllegalStateException("device is closed");
        }
    }

    boolean isClosed() { return closed; }

    long uniformBufferOffsetAlignment() { return uniformBufferOffsetAlignment; }

    void activateCapabilities() {
        requireOpen();
        GL.setCapabilities(capabilities);
    }

    private <T extends OpenGLResource> T track(T resource) {
        resources.add(resource);
        return resource;
    }

    private <T extends OpenGLResource> T owned(Object resource, Class<T> type, String label) {
        if (!type.isInstance(resource)) {
            throw new IllegalArgumentException(label + " was not created by the OpenGL backend");
        }
        T result = type.cast(resource);
        if (result.device != this) {
            throw new IllegalArgumentException(label + " belongs to another device");
        }
        result.requireAlive();
        return result;
    }

    OpenGLRenderTargetAccess ownedTarget(Object resource, String label) {
        if (!(resource instanceof OpenGLRenderTargetAccess target)) {
            throw new IllegalArgumentException(label + " does not expose OpenGL target access");
        }
        if (target.device() != this) {
            throw new IllegalArgumentException(label + " belongs to another device");
        }
        target.width();
        if (target.colorFormats().size() != 1) {
            throw new IllegalArgumentException(label + " must expose exactly one color format");
        }
        return target;
    }

    @Override
    public Buffer createBuffer(BufferDescriptor descriptor) {
        return createBufferInternal(Objects.requireNonNull(descriptor, "descriptor"), null);
    }

    @Override
    public Buffer createBuffer(BufferDescriptor descriptor, ByteBuffer initialData) {
        Objects.requireNonNull(initialData, "initialData");
        return createBufferInternal(Objects.requireNonNull(descriptor, "descriptor"), initialData.duplicate());
    }

    private Buffer createBufferInternal(BufferDescriptor descriptor, ByteBuffer data) {
        requireOpen();
        if (data != null && data.remaining() > descriptor.size()) {
            throw new IllegalArgumentException("initial data exceeds buffer size");
        }
        activateCapabilities();
        int handle = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, handle);
        if (data == null) {
            glBufferData(GL_ARRAY_BUFFER, descriptor.size(), GL_DYNAMIC_DRAW);
        } else {
            OpenGLUploadMemory.withNativeBuffer(data, uploadData -> {
                if (uploadData.remaining() == descriptor.size()) {
                    glBufferData(GL_ARRAY_BUFFER, uploadData, GL_STATIC_DRAW);
                } else {
                    glBufferData(GL_ARRAY_BUFFER, descriptor.size(), GL_DYNAMIC_DRAW);
                    glBufferSubData(GL_ARRAY_BUFFER, 0, uploadData);
                }
            });
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        return track(new OpenGLBuffer(this, handle, descriptor));
    }

    @Override
    public Texture createTexture(TextureDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return createTextureInternal(descriptor, null, ResourceState.UNDEFINED);
    }

    @Override
    public Texture createTexture(TextureDescriptor descriptor, ByteBuffer initialData, ResourceState initialState) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(initialData, "initialData");
        Objects.requireNonNull(initialState, "initialState");
        validateInitialTexture(descriptor, initialData, initialState);
        return createTextureInternal(descriptor, initialData.duplicate(), initialState);
    }

    private Texture createTextureInternal(TextureDescriptor descriptor, ByteBuffer initialData, ResourceState initialState) {
        requireOpen();
        activateCapabilities();
        int handle = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, handle);
        if (initialData == null) {
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    OpenGLMappings.textureInternalFormat(descriptor.format()),
                    descriptor.width(),
                    descriptor.height(),
                    0,
                    OpenGLMappings.textureExternalFormat(descriptor.format()),
                    OpenGLMappings.textureExternalType(descriptor.format()),
                    (ByteBuffer) null);
        } else {
            OpenGLUploadMemory.withNativeBuffer(initialData, uploadData -> glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    OpenGLMappings.textureInternalFormat(descriptor.format()),
                    descriptor.width(),
                    descriptor.height(),
                    0,
                    OpenGLMappings.textureExternalFormat(descriptor.format()),
                    OpenGLMappings.textureExternalType(descriptor.format()),
                    uploadData));
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        OpenGLTexture texture = new OpenGLTexture(this, handle, descriptor);
        texture.state = initialState;
        return track(texture);
    }

    private static void validateInitialTexture(
            TextureDescriptor descriptor, ByteBuffer initialData, ResourceState initialState) {
        if (descriptor.format().isDepth()) {
            throw new IllegalArgumentException("CPU initialization of depth textures is not supported");
        }
        long required = textureByteCount(descriptor);
        if (initialData.remaining() != required) {
            throw new IllegalArgumentException("initial data must contain exactly " + required + " bytes");
        }
        switch (initialState) {
            case COLOR_ATTACHMENT_WRITE -> requireTextureUsage(descriptor, TextureUsage.COLOR_ATTACHMENT, initialState);
            case DEPTH_ATTACHMENT_WRITE -> requireTextureUsage(descriptor, TextureUsage.DEPTH_ATTACHMENT, initialState);
            case SAMPLED_READ -> requireTextureUsage(descriptor, TextureUsage.SAMPLED, initialState);
            case COPY_SRC -> requireTextureUsage(descriptor, TextureUsage.COPY_SRC, initialState);
            case COPY_DST -> requireTextureUsage(descriptor, TextureUsage.COPY_DST, initialState);
            case UNDEFINED, UNIFORM_READ, VERTEX_READ, INDEX_READ ->
                    throw new IllegalArgumentException(initialState + " is not a valid initialized texture state");
        }
    }

    private static long textureByteCount(TextureDescriptor descriptor) {
        long texels = Math.multiplyExact((long) descriptor.width(), descriptor.height());
        int bytesPerTexel = switch (descriptor.format()) {
            case RGBA8_UNORM, BGRA8_UNORM -> 4;
            case D32_FLOAT -> 4;
        };
        return Math.multiplyExact(texels, bytesPerTexel);
    }

    private static void requireTextureUsage(TextureDescriptor descriptor, TextureUsage usage, ResourceState state) {
        if (!descriptor.usage().contains(usage)) {
            throw new IllegalArgumentException(state + " requires texture usage " + usage);
        }
    }

    @Override
    public ShaderTarget shaderTarget() {
        requireOpen();
        return shaderTarget;
    }

    @Override
    public Shader createShader(ShaderDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        if (!(descriptor.code() instanceof GlslShaderCode glsl)) {
            throw new IllegalArgumentException("OpenGL backend requires GlslShaderCode");
        }
        if (!"main".equals(descriptor.entryPoint())) {
            throw new IllegalArgumentException("desktop GLSL exposes the fixed entry point 'main'");
        }
        activateCapabilities();
        int nativeStage = switch (descriptor.stage()) {
            case VERTEX -> GL_VERTEX_SHADER;
            case FRAGMENT -> GL_FRAGMENT_SHADER;
        };
        int shader = glCreateShader(nativeStage);
        glShaderSource(shader, glsl.code());
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalArgumentException("GLSL compilation failed: " + log);
        }
        return track(new OpenGLShader(this, shader, descriptor.stage()));
    }

    @Override
    public Sampler createSampler(SamplerDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        activateCapabilities();
        int sampler = glGenSamplers();
        glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, filter(descriptor.minFilter()));
        glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, filter(descriptor.magFilter()));
        int wrap = addressMode(descriptor.addressMode());
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, wrap);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, wrap);
        return track(new OpenGLSampler(this, sampler));
    }

    private static int filter(SamplerDescriptor.Filter filter) {
        return filter == SamplerDescriptor.Filter.LINEAR ? GL_LINEAR : GL_NEAREST;
    }

    private static int addressMode(SamplerDescriptor.AddressMode mode) {
        return mode == SamplerDescriptor.AddressMode.REPEAT ? GL_REPEAT : GL_CLAMP_TO_EDGE;
    }

    @Override
    public GraphicsState createGraphicsState(GraphicsStateDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        OpenGLShader vertex = owned(descriptor.vertexShader(), OpenGLShader.class, "vertex shader");
        OpenGLShader fragment = owned(descriptor.fragmentShader(), OpenGLShader.class, "fragment shader");
        activateCapabilities();
        int program = linkProgram(vertex, fragment);
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        for (VertexAttribute attribute : descriptor.vertexLayout().attributes()) {
            glEnableVertexAttribArray(attribute.location());
            glVertexAttribFormat(attribute.location(), attribute.format().bytes() / Float.BYTES, GL_FLOAT, false, attribute.offset());
            glVertexAttribBinding(attribute.location(), attribute.binding());
        }
        for (VertexBinding binding : descriptor.vertexLayout().bindings()) {
            glVertexBindingDivisor(binding.binding(), binding.inputRate() == VertexInputRate.PER_INSTANCE ? 1 : 0);
        }
        glBindVertexArray(0);
        OpenGLBindingPlan plan = createBindingPlan(program, descriptor.bindingLayouts());
        return track(new OpenGLGraphicsState(this, program, vao, descriptor, plan));
    }

    private int linkProgram(OpenGLShader... shaders) {
        int program = glCreateProgram();
        for (OpenGLShader shader : shaders) {
            if (shader != null) {
                glAttachShader(program, shader.handle);
            }
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalArgumentException("OpenGL program link failed: " + log);
        }
        for (OpenGLShader shader : shaders) {
            if (shader != null) {
                glDetachShader(program, shader.handle);
            }
        }
        return program;
    }

    private OpenGLBindingPlan createBindingPlan(int program, List<BindingLayout> layouts) {
        IdentityHashMap<Binding<?>, Integer> slots = new IdentityHashMap<>();
        Map<BindingType, Integer> next = new java.util.EnumMap<>(BindingType.class);
        for (BindingType type : BindingType.values()) next.put(type, 0);

        glUseProgram(program);
        for (BindingLayout layout : layouts) {
            for (Binding<?> binding : layout.bindings()) {
                int slot = next.get(binding.type());
                if (slot >= OpenGLMappings.maxBindingCount(binding.type())) {
                    glUseProgram(0);
                    throw new IllegalArgumentException("OpenGL binding limit exceeded for " + binding.type());
                }
                next.put(binding.type(), slot + 1);
                slots.put(binding, slot);
                switch (binding.type()) {
                    case SAMPLED_TEXTURE -> {
                        int location = glGetUniformLocation(program, binding.name());
                        if (location >= 0) glUniform1i(location, slot);
                    }
                    case UNIFORM_BUFFER -> {
                        int index = glGetUniformBlockIndex(program, binding.name());
                        if (index != GL_INVALID_INDEX) glUniformBlockBinding(program, index, slot);
                    }
                }
            }
        }
        glUseProgram(0);
        return new OpenGLBindingPlan(layouts, slots);
    }

    @Override
    public BindingSet createBindingSet(BindingSetDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        for (Object value : descriptor.values().values()) {
            if (value instanceof BufferBinding buffer) {
                owned(buffer.buffer(), OpenGLBuffer.class, "bound buffer");
                OpenGLValidation.validateUniformOffset(buffer.offset(), uniformBufferOffsetAlignment);
            } else if (value instanceof TextureBinding texture) {
                owned(texture.texture(), OpenGLTexture.class, "bound texture");
                owned(texture.sampler(), OpenGLSampler.class, "bound sampler");
            }
        }
        return track(new OpenGLBindingSet(this, descriptor));
    }

    @Override
    public RenderTarget createRenderTarget(RenderTargetDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireOpen();
        List<OpenGLTexture> colors = descriptor.colorAttachments().stream()
                .map(texture -> owned(texture, OpenGLTexture.class, "color attachment"))
                .toList();
        OpenGLTexture depth = descriptor.depthAttachment() == null
                ? null
                : owned(descriptor.depthAttachment(), OpenGLTexture.class, "depth attachment");
        activateCapabilities();
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colors.get(0).handle, 0);
        if (depth != null) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth.handle, 0);
        }
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            glDeleteFramebuffers(framebuffer);
            throw new IllegalArgumentException("incomplete OpenGL framebuffer: 0x" + Integer.toHexString(status));
        }
        int width = colors.get(0).width();
        int height = colors.get(0).height();
        return track(new OpenGLRenderTarget(
                this,
                framebuffer,
                colors,
                depth,
                width,
                height,
                colors.stream().map(Texture::format).toList(),
                depth == null ? null : depth.format()));
    }

    @Override
    public void present(RenderTarget target) {
        requireOpen();
        OpenGLRenderTargetAccess glTarget = ownedTarget(Objects.requireNonNull(target, "target"), "render target");
        activateCapabilities();
        glTarget.present();
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        requireOpen();
        return new OpenGLCommandEncoder(this, config.validation());
    }

    @Override
    public void submit(CommandList commandList) {
        requireOpen();
        if (!(commandList instanceof OpenGLCommandList list) || list.device != this) {
            throw new IllegalArgumentException("command list belongs to another backend/device");
        }
        list.beginSubmission();
        try {
            activateCapabilities();
            OpenGLExecutionContext context = new OpenGLExecutionContext(this);
            for (OpenGLCommand command : list.commands) {
                command.execute(context);
            }
            glFlush();
            list.markSubmitted();
        } catch (RuntimeException | Error failure) {
            list.markFailed();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        activateCapabilities();
        try {
            glFinish();
            for (int i = resources.size() - 1; i >= 0; i--) {
                OpenGLResource resource = resources.get(i);
                if (!resource.isClosed()) resource.close();
            }
        } finally {
            if (nativeDebug != null) nativeDebug.close();
            closed = true;
        }
    }
}
