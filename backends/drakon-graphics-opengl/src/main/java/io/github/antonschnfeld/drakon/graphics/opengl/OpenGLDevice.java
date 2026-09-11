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
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.*;

/**
 * LWJGL OpenGL 4.3 implementation of {@link GraphicsDevice} used by the backend spike.
 *
 * <p>The device owns one GLFW-created OpenGL context. That one-context ownership
 * model is deliberately a spike constraint rather than a new portable contract:
 * it lets the real OpenGL mapping exercise the existing RHI without introducing
 * window/context types into {@code drakon-graphics}.</p>
 *
 * <p>Commands are recorded as Java objects and replayed on submission. This is
 * intentionally not the final OpenGL hot-path design; it preserves the portable
 * command-list semantics while making backend mapping and validation observable.</p>
 */
public final class OpenGLDevice implements GraphicsDevice {
    private static final Object GLFW_LOCK = new Object();
    private static int glfwUsers;

    private final GraphicsDeviceConfig config;
    private final long window;
    private final boolean visible;
    private final GLCapabilities capabilities;
    private final OpenGLShaderTarget shaderTarget = new OpenGLShaderTarget(4, 3, 430);
    private final List<OpenGLResource> resources = new ArrayList<>();
    private final OpenGLRenderTarget defaultTarget;
    private boolean closed;

    private OpenGLDevice(GraphicsDeviceConfig config, long window, boolean visible, GLCapabilities capabilities) {
        this.config = config;
        this.window = window;
        this.visible = visible;
        this.capabilities = capabilities;
        defaultTarget = visible
                ? track(new OpenGLRenderTarget(
                        this, 0, List.of(), null, true, window, 0, 0,
                        List.of(TextureFormat.RGBA8_UNORM), null))
                : null;
    }

    static OpenGLDevice create(
            GraphicsDeviceConfig config,
            int width,
            int height,
            String title,
            boolean visible) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(title, "title");
        acquireGlfw();
        long handle = 0L;
        try {
            synchronized (GLFW_LOCK) {
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
                glfwWindowHint(GLFW_VISIBLE, visible ? GLFW_TRUE : GLFW_FALSE);
                handle = glfwCreateWindow(width, height, title, 0L, 0L);
            }
            if (handle == 0L) {
                throw new IllegalStateException("GLFW could not create an OpenGL 4.3 core context");
            }
            glfwMakeContextCurrent(handle);
            GLCapabilities caps = GL.createCapabilities();
            if (!caps.OpenGL43) {
                throw new IllegalStateException("OpenGL 4.3 is required by the spike backend");
            }
            if (visible) {
                glfwSwapInterval(0);
            }
            return new OpenGLDevice(config, handle, visible, caps);
        } catch (RuntimeException | Error failure) {
            if (handle != 0L) {
                glfwDestroyWindow(handle);
            }
            releaseGlfw();
            throw failure;
        }
    }

    private static void acquireGlfw() {
        synchronized (GLFW_LOCK) {
            if (glfwUsers == 0 && !glfwInit()) {
                throw new IllegalStateException("GLFW initialization failed");
            }
            glfwUsers++;
        }
    }

    private static void releaseGlfw() {
        synchronized (GLFW_LOCK) {
            glfwUsers--;
            if (glfwUsers == 0) {
                glfwTerminate();
            }
        }
    }

    void requireOpen() {
        if (closed) {
            throw new IllegalStateException("device is closed");
        }
    }

    boolean isClosed() { return closed; }

    void makeCurrent() {
        requireOpen();
        glfwMakeContextCurrent(window);
        GL.setCapabilities(capabilities);
    }

    int framebufferWidth(long windowHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(windowHandle, width, height);
            return Math.max(width.get(0), 1);
        }
    }

    int framebufferHeight(long windowHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(windowHandle, width, height);
            return Math.max(height.get(0), 1);
        }
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

    /** Returns the GLFW window handle owned by this spike device. */
    public long windowHandle() {
        requireOpen();
        return window;
    }

    /** Returns whether the GLFW window has requested closure. */
    public boolean shouldClose() {
        requireOpen();
        return glfwWindowShouldClose(window);
    }

    /** Polls GLFW events for the spike-created window. */
    public void pollEvents() {
        requireOpen();
        glfwPollEvents();
    }

    /**
     * Returns framebuffer zero as the presentation-backed render target.
     *
     * @return the stable target facade for this window's default framebuffer
     * @throws IllegalStateException if this is the generic invisible/offscreen device
     */
    public RenderTarget defaultRenderTarget() {
        requireOpen();
        if (defaultTarget == null) {
            throw new IllegalStateException("this device was not created with a visible presentation window");
        }
        return defaultTarget;
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
        makeCurrent();
        int handle = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, handle);
        ByteBuffer uploadData = data == null ? null : directUploadData(data);
        try {
            if (uploadData == null) {
                glBufferData(GL_ARRAY_BUFFER, descriptor.size(), GL_DYNAMIC_DRAW);
            } else if (uploadData.remaining() == descriptor.size()) {
                glBufferData(GL_ARRAY_BUFFER, uploadData, GL_STATIC_DRAW);
            } else {
                glBufferData(GL_ARRAY_BUFFER, descriptor.size(), GL_DYNAMIC_DRAW);
                glBufferSubData(GL_ARRAY_BUFFER, 0, uploadData);
            }
        } finally {
            freeUploadData(data, uploadData);
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
        makeCurrent();
        int handle = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, handle);
        ByteBuffer uploadData = initialData == null ? null : directUploadData(initialData);
        try {
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    OpenGLMappings.textureInternalFormat(descriptor.format()),
                    descriptor.width(),
                    descriptor.height(),
                    0,
                    OpenGLMappings.textureExternalFormat(descriptor.format()),
                    OpenGLMappings.textureExternalType(descriptor.format()),
                    uploadData);
        } finally {
            freeUploadData(initialData, uploadData);
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

    /**
     * Returns data suitable for an LWJGL native call without changing the caller-visible buffer.
     *
     * <p>LWJGL requires native buffers for pointer-based overloads. Heap buffers are copied into
     * temporary native memory that is released at the end of the enclosing upload operation.</p>
     */
    private static ByteBuffer directUploadData(ByteBuffer data) {
        if (data.isDirect()) {
            return data;
        }
        ByteBuffer nativeData = MemoryUtil.memAlloc(data.remaining());
        nativeData.put(data.duplicate());
        return nativeData.flip();
    }

    private static void freeUploadData(ByteBuffer originalData, ByteBuffer uploadData) {
        if (originalData != null && !originalData.isDirect()) {
            MemoryUtil.memFree(uploadData);
        }
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
        makeCurrent();
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
        makeCurrent();
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
        makeCurrent();
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
        makeCurrent();
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
                false,
                0L,
                width,
                height,
                colors.stream().map(Texture::format).toList(),
                depth == null ? null : depth.format()));
    }

    @Override
    public void present(RenderTarget target) {
        requireOpen();
        OpenGLRenderTarget glTarget = owned(target, OpenGLRenderTarget.class, "render target");
        if (!glTarget.presentable()) {
            throw new IllegalArgumentException("render target is not presentation-backed");
        }
        makeCurrent();
        glfwSwapBuffers(glTarget.window());
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
        list.markSubmitted();
        makeCurrent();
        OpenGLExecutionContext context = new OpenGLExecutionContext(this);
        for (OpenGLCommand command : list.commands) {
            command.execute(context);
        }
        glFlush();
    }

    public String backendName() { requireOpen(); return "LWJGL OpenGL 4.3"; }

    @Override
    public void close() {
        if (closed) return;
        makeCurrent();
        glFinish();
        for (int i = resources.size() - 1; i >= 0; i--) {
            OpenGLResource resource = resources.get(i);
            if (!resource.isClosed()) resource.close();
        }
        closed = true;
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0L);
        glfwDestroyWindow(window);
        releaseGlfw();
    }
}
