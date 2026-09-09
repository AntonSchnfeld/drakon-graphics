# drakon-graphics — Real Backend Spike Findings (handoff state)

## Purpose

This spike exists to pressure-test the portable `drakon-graphics` API against real LWJGL OpenGL and Vulkan mappings. It is not a production backend release. A forced core API change is considered useful evidence, not a failure of the spike.

The practical definition of done remains:

1. broad real OpenGL coverage across the existing graphics scenarios;
2. narrow but deep Vulkan coverage of graphics, compute, resource transitions, bindings, offscreen targets, and window presentation;
3. real GLFW-backed presentation for both APIs;
4. credible Vulkan multi-frame behavior without exposing a public swapchain/frame token unless the implementation proves one is necessary;
5. a written record of every abstraction that maps cleanly, maps awkwardly, or must change.

## Validation status at handoff

### CPU-provided resource initialization (2026-09-10)

`GraphicsDevice` accepts both heap and direct `ByteBuffer` instances for
creation-time buffer and texture initialization. LWJGL's requirement for a
direct buffer at native OpenGL call sites is therefore an OpenGL backend
implementation detail, not a portable API requirement. The OpenGL mapping
passes direct inputs through and copies heap inputs into temporary native
allocations for the duration of the upload, preserving the caller buffer's
position and limit in both cases. Resource payloads must not use
`MemoryStack`, because they may be substantially larger than native-call
scratch storage.

`BGRA8_UNORM` initialization now uses OpenGL's `GL_BGRA` external upload
format, while retaining `GL_RGBA8` internal storage. This makes the portable
blue-green-red-alpha byte order map correctly to OpenGL.

The exact byte representation for multi-byte floating texture initialization
(notably `RGBA16_FLOAT`) remains an unresolved portable contract detail. This
ticket does not define or alter it.

### Local runtime corrections (2026-09-09)

The first local smoke run exposed failures that compilation and the recording
probes could not detect:

- The harness triangle used clockwise vertices with back-face culling enabled.
  Reordering the vertices (and their colours) to counter-clockwise restores the
  OpenGL triangle without disabling culling.
- The fatal `igvk64.dll` access violation occurred in `vkCreateShaderModule`.
  `SpirvShaderCode` intentionally copies into a heap buffer, which cannot be
  passed as an LWJGL native pointer. The Vulkan backend now allocates native
  memory, copies the bytes, and frees it after shader-module creation. The
  portable shader-code ownership contract stays unchanged.
- `VkPresentInfoKHR.swapchainCount` remained zero: LWJGL's `pSwapchains` and
  `pImageIndices` setters do not infer this shared count. No images were
  presented, eventually blocking `vkAcquireNextImageKHR`. It is now explicitly
  one.
- `VkSubmitInfo.waitSemaphoreCount` likewise needs an explicit value when
  setting the acquisition semaphore and its destination-stage mask. The first
  rendering submission now actually waits for image acquisition.
- The negative-height Vulkan viewport must be paired with counter-clockwise
  front faces for this portable winding convention. The prior clockwise
  setting culled the corrected triangle.

The JDK 25 native-access and LWJGL Unsafe warnings are separate from these
rendering defects. `--enable-native-access=ALL-UNNAMED` grants native access on
newer JDKs; it does not repair invalid native pointers or graphics state.

### Validated here

The portable core was recompiled after the backend-spike changes with:

```text
javac --release 21 -Xlint:all -Werror
javadoc -Xdoclint:all -Werror
```

The A-J probe suite and API contract checks pass.

Run locally without Maven/LWJGL using:

```bash
./validate-core.sh
```

### Not validated here

The execution environment used to prepare this handoff does not contain Maven or cached LWJGL artifacts, and the backend modules could therefore not be compiled against the actual LWJGL jars or run against a GPU here.

The Maven project is intentionally configured so that the recipient's first `mvn verify` performs the missing real dependency compilation. Any failures from that build are part of the spike evidence and should be fixed rather than bypassed.

## Backend modules

```text
drakon-graphics
    portable API + probe regression suite

drakon-graphics-opengl
    LWJGL OpenGL 4.3 + GLFW spike backend

drakon-graphics-vulkan
    LWJGL Vulkan 1.3 + GLFW spike backend

drakon-graphics-spike
    executable real-window smoke harness
```

`drakon-graphics-opengl` and `drakon-graphics-vulkan` each register their `GraphicsBackend` implementation through `META-INF/services`.

LWJGL is currently pinned to 3.4.3.

## Core API changes forced by real-backend pressure

### 1. Texture and sampler are one logical sampled binding

Iteration 6 modeled sampled textures and samplers as independent binding types. That looked Vulkan-friendly but became awkward on OpenGL, where an ordinary sampler uniform represents a texture-unit association and the texture and sampler object are naturally bound together for that unit.

The spike therefore changed:

```text
Binding<Texture> sampledTexture(...)
Binding<Sampler> sampler(...)
```

to:

```text
Binding<TextureBinding> sampledTexture(...)
```

with:

```java
new TextureBinding(texture, sampler)
```

This maps naturally to:

- OpenGL: texture + sampler object bound to the same texture unit;
- Vulkan: combined image sampler descriptor.

This is a simplification: one public binding type was removed and one small value record was added.

### 2. `Binding.name()` is semantically meaningful

The OpenGL mapping currently needs a stable shader-resource name to associate a logical binding with a GLSL resource when the native API does not have Vulkan-style descriptor-set metadata.

The Javadoc therefore no longer describes `Binding.name()` as merely a debug label. The name is a stable shader resource identifier. A future shader/reflection layer should preserve and validate this rather than forcing each backend to invent name/location conventions.

### 3. `BGRA8_UNORM` was required

Vulkan swapchains commonly expose `VK_FORMAT_B8G8R8A8_UNORM`. The portable API previously only had `RGBA8_UNORM`.

Reporting a BGRA swapchain as RGBA would make `RenderTarget.colorFormats()` and `GraphicsStateDescriptor` compatibility dishonest, so the spike added:

```java
TextureFormat.BGRA8_UNORM
```

This is backend-forced vocabulary, not speculative expansion.

## Presentation result so far

The minimal Iteration 6 presentation design is surviving the Vulkan implementation attempt.

The public API remains:

```java
RenderTarget windowTarget = /* backend bootstrap */;
renderer.execute(pipeline);
device.present(windowTarget);
```

No public types have been added for:

```text
Swapchain
Surface
PresentationTarget
PresentationFrame
FrameToken
AcquireResult
```

### Vulkan mapping

Internally, a presentation-backed Vulkan `RenderTarget` represents a stable façade over rotating swapchain images/views.

The backend can:

1. acquire the current swapchain image when the target is first used for the frame;
2. make the first swapchain-touching submission wait on the image-acquired semaphore;
3. rely on queue ordering for later submissions touching the same target;
4. let `present(target)` emit the final render-finished synchronization submission;
5. call `vkQueuePresentKHR` for the internally tracked acquired image.

This is deliberately being tested because a trivial `vkDeviceWaitIdle()` implementation would falsely make the API look viable.

### Presentation-owned states remain internal

The portable `ResourceState.PRESENT` remains removed. Applications do not possess the Vulkan swapchain image as a public `Texture`, so making them transition that hidden image would violate the abstraction.

Application-owned textures keep explicit transitions. Backend-owned presentation images keep acquire/layout/present transitions inside the backend.

## Synchronization findings

The current `ResourceState` abstraction can be mapped to Vulkan barriers, but shader-readable/writable states do not encode exactly which shader stages will consume a resource.

The Vulkan spike therefore has to use conservative stage masks in some cases.

That is currently classified as:

```text
correctness: potentially expressible
performance precision: unresolved
```

Do not add shader-stage information to `ResourceState` merely for theoretical purity. First measure whether the conservative mapping is materially harmful or whether binding/state metadata already provides enough information to infer tighter barriers.

## Coordinate/winding finding

Drakon defines an upper-left-oriented viewport contract while preserving counter-clockwise front faces.

A Vulkan backend can implement the OpenGL-style Y orientation within an upper-left-positioned viewport using a negative viewport height. In this backend that must be paired with `VK_FRONT_FACE_COUNTER_CLOCKWISE`, as confirmed by the local triangle smoke test. The earlier assumption that this required clockwise front faces was incorrect.

This belongs entirely in the backend; the portable winding contract should not change.

## Shader architecture result

The backend spike preserves the established separation:

```text
ShaderCode
    GlslShaderCode
    SpirvShaderCode

ShaderDescriptor
    stage
    entryPoint
    code

GraphicsDevice.createShader(...)
    -> device-owned Shader
```

The OpenGL backend advertises GLSL and consumes `GlslShaderCode`.

The Vulkan backend advertises SPIR-V and consumes `SpirvShaderCode`.

The executable Vulkan smoke test uses LWJGL Shaderc only as test harness infrastructure:

```text
GLSL test string
    -> Shaderc
    -> SPIR-V
    -> SpirvShaderCode
    -> Vulkan backend
```

Shaderc is **not** a dependency of `drakon-graphics-vulkan`. The future `drakon-shaders` module remains responsible for real authoring-language compilation.

See `SHADER_ARCHITECTURE.md` for the preserved compiler architecture.

## OpenGL spike scope

The OpenGL backend currently attempts real implementations for the existing core resource/command vocabulary, including:

- GLFW OpenGL 4.3 core context bootstrap;
- buffers;
- textures;
- GLSL shader compilation/linking;
- samplers;
- graphics state;
- compute state;
- binding sets;
- vertex/index bindings;
- framebuffer-backed offscreen targets;
- default-framebuffer presentation target;
- draw / indexed draw / instanced and indirect paths represented by the API;
- compute dispatch;
- texture copy;
- resource-transition validation / OpenGL memory barriers where required;
- `present(RenderTarget)` via GLFW buffer swap for the default target.

The handoff smoke harness deliberately starts with a triangle rather than claiming A-J are already proven on the real backend.

## Vulkan spike scope

The Vulkan backend currently attempts the high-pressure subset needed to test the API shape:

- Vulkan 1.3 instance/device selection;
- GLFW surface creation;
- graphics/present queue discovery;
- swapchain creation and recreation machinery;
- multi-frame synchronization scaffolding;
- buffers and device memory;
- images, views, samplers and device memory;
- SPIR-V shader modules;
- graphics/compute pipeline state mapping;
- descriptor-set/binding mapping;
- command buffers;
- dynamic rendering / attachment handling;
- explicit image/buffer barriers;
- offscreen render targets;
- swapchain-backed `RenderTarget`;
- submit and present lifecycle.

This is intentionally not a production allocator, descriptor-cache system, staging subsystem, frame graph, or async scheduler.

## Explicit non-goals for the spike

Do not judge the spike on production-quality performance in these areas yet:

- Vulkan memory suballocation;
- sophisticated descriptor caching;
- bindless resources;
- transient resource allocators;
- asynchronous upload queues;
- pipeline-cache serialization;
- multithreaded recording;
- async compute scheduling;
- shader hot reload;
- render graph;
- production validation/profiling infrastructure.

## Creation-time texture initialization evidence

The real OpenGL and Vulkan texture paths demonstrated a missing portable
capability while implementing the textured-mesh spike: buffers could receive
CPU data at creation time, but textures could only be created with undefined
contents. The API therefore provides creation-time initialization of tightly
packed mip-level-zero texels and returns the texture in the caller's requested
portable access state. Vulkan's temporary staging buffer and transfer-destination
image usage are backend implementation details; callers do not need to declare
`COPY_DST` solely for this initialization.

This evidence does not yet justify a general `writeTexture(...)` API,
`copyBufferToTexture(...)`, mapped textures, staging/upload queues, or texture
streaming APIs. Those remain future decisions driven by additional backend and
workload evidence.

## Textured indexed-mesh result (DG-SPIKE-002)

The real OpenGL and Vulkan presentation spikes now execute one shared portable
workload: a counter-clockwise quad with four interleaved position/UV vertices,
six unsigned 16-bit indices, and one `drawIndexed(6, 1, 0, 0, 0)` call. The
vertex and index buffers are initialized through `GraphicsDevice`, then
transitioned to `VERTEX_READ` and `INDEX_READ` before rendering.

The workload initializes a 4-by-4 `RGBA8_UNORM` texture with sharply distinct
red, green, blue, and yellow quadrants and creates it directly in
`SAMPLED_READ`. One `BindingLayout` declares the fragment-stage
`texturePattern` sampled-texture binding; its `BindingSet` supplies the current
`TextureBinding(texture, sampler)` pair. The graphics state declares separate
position and UV attributes, and the same portable recording path binds the
state, vertex/index buffers, and binding set before submission and
`GraphicsDevice.present(RenderTarget)`.

Visual inspection of both GLFW windows confirmed identical output: a correctly
oriented quad with red upper-left, green upper-right, blue lower-left, and
yellow lower-right quadrants. This provides real-backend evidence that the
current combined texture-and-sampler binding maps correctly to an OpenGL
texture unit and sampler object and to a Vulkan combined image-sampler
descriptor. No additional public API change was required.

The code should avoid obviously pathological behavior, but optimization work should wait until the portable API has survived real execution.

## What to run next

First compile everything:

```bash
mvn verify
```

Then install the reactor artifacts locally and run each backend separately:

```bash
mvn install
mvn -f spike/pom.xml exec:java -Dexec.args=opengl
mvn -f spike/pom.xml exec:java -Dexec.args=vulkan
```

Windows shortcuts are also included:

```text
run-opengl.cmd
run-vulkan.cmd
```

The expected smoke result is a GLFW window containing a large four-quadrant
textured quad: red upper-left, green upper-right, blue lower-left, and yellow
lower-right. Close the window to end the backend run.

## What feedback is most useful

When compilation fails, preserve:

1. complete Maven compiler error;
2. file and line;
3. LWJGL method overload/signature Maven resolved;
4. whether failure is OpenGL, Vulkan, or spike harness.

When runtime fails, preserve:

1. exception + full stack trace;
2. validation-layer output if available;
3. GPU and driver;
4. Vulkan API version / OpenGL version;
5. whether a window appeared;
6. whether failure happened at bootstrap, shader/state creation, submit, resize, or present.

Those failures are exactly the evidence needed for the next iteration.
