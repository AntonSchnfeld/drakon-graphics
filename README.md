# drakon-graphics

A small, explicit, backend-neutral graphics API for Java 25.

`drakon-graphics` sits between raw LWJGL graphics APIs and a full game engine. It provides a portable rendering interface for building real-time renderers without forcing application rendering code to depend directly on OpenGL or Vulkan.

The goal is simple:

> Write the renderer once. Choose the graphics backend separately.

OpenGL 4.3 and Vulkan 1.3 are currently supported through independent backend modules.

> [!NOTE]
> `drakon-graphics` is currently pre-0.1.0 and under active development. Public APIs may still change before the first release.

## Vision

Java has excellent access to native graphics APIs through LWJGL, but using them directly means tying renderer architecture to a particular backend and taking on a large amount of backend-specific lifecycle, synchronization, and state management.

`drakon-graphics` provides a deliberately small layer above those APIs.

It is intended to be:

- **Backend-neutral** — portable rendering code should not contain OpenGL or Vulkan calls.
- **Explicit** — resources, state transitions, command recording, submission, and presentation have visible contracts.
- **Low-level** — the library does not impose a scene graph, ECS, camera system, material system, or asset model.
- **Composable** — applications remain free to build their own renderer architecture on top.
- **Predictable** — GPU resource ownership and lifetime are part of the API rather than hidden behind global state.
- **Practical** — abstractions are added when required by real backend behavior rather than to imitate a larger engine API.

`drakon-graphics` is a graphics library, not a game engine.

## Current architecture

The project is split into independent Maven modules:

```text
drakon-graphics-build
├── drakon-graphics
├── backends/
│   ├── drakon-graphics-opengl
│   └── drakon-graphics-vulkan
└── spike/
```

Public modules:

```text
io.github.antonschnfeld:drakon-graphics
io.github.antonschnfeld:drakon-graphics-opengl
io.github.antonschnfeld:drakon-graphics-vulkan
```

The root module is only a Maven reactor aggregator.

`spike` contains development and backend-validation applications and is not intended to be published as a library artifact.

## Core concepts

### GraphicsDevice

A `GraphicsDevice` owns GPU resources and provides the backend-neutral operations used to create resources, record work, submit commands, and present render targets.

### RenderTarget

`RenderTarget` is the portable destination for rendering.

A target may represent ordinary texture attachments or a presentation destination supplied by an external backend integration.

There is no portable `Window`, `Surface`, or `Swapchain` abstraction.

Window-system ownership deliberately remains outside `drakon-graphics`.

### Commands

GPU work is recorded through a `CommandEncoder` and finished into a `CommandList`.

Both have deterministic lifetimes through `AutoCloseable`.

A finished command list is single-submit: successful submission transfers ownership of its native command resources to the device until the GPU no longer needs them.

Runtime buffer updates are recorded in the same ordered command stream as draws:

```java
commands.writeBuffer(uniformBuffer, 0, frameData);
commands.beginRendering(renderingInfo);
// bind and draw using the updated buffer
commands.endRendering();
```

The selected source bytes are captured during recording, so the caller may reuse
`frameData` immediately after `writeBuffer` returns.

### Resource states

Application-visible resource usage is explicit.

Textures and buffers transition between portable states such as:

- `VERTEX_READ`
- `INDEX_READ`
- `UNIFORM_READ`
- `SAMPLED_READ`
- `COLOR_ATTACHMENT_WRITE`
- `DEPTH_ATTACHMENT_WRITE`
- `COPY_SRC`
- `COPY_DST`

Backends map those states to the synchronization and layout mechanisms required by their native API.

### Render pipelines and passes

`RenderPipeline` and `RenderPass` provide lightweight composition of rendering work without introducing engine-level concepts into the graphics layer.

The library does not require a scene, world, camera, material model, or ECS.

## Backend support

### OpenGL

The OpenGL backend currently targets OpenGL 4.3.

The application or platform layer owns the OpenGL context. A compatible context must be current when the device is created and while it is used.

The backend does not create or destroy windows or contexts.

### Vulkan

The Vulkan backend currently targets Vulkan 1.3.

Headless devices can be created independently of presentation.

Presentation-capable devices use an external surface integration during bootstrap so that physical-device and queue selection can verify presentation support.

Swapchain management, image acquisition, synchronization, and presentation remain Vulkan backend responsibilities; window-system state remains external.

## Presentation

Presentation uses the same portable operation regardless of backend:

```java
device.present(renderTarget);
```

Presentation capability is intentionally not represented by a separate core target subtype.

A compatible backend or platform integration may provide a presentable `RenderTarget`; ordinary texture-backed targets remain valid render targets but are not necessarily presentable.

This also avoids treating one target as a privileged global or "default" render target.

## Shaders

Shader compilation is intentionally separate from the graphics API.

The core currently accepts shader representations such as:

- GLSL source
- SPIR-V bytecode

`drakon-graphics` creates GPU shader resources from those representations but does not contain a general shader compiler or shader-language translation layer.

This keeps graphics-device responsibilities separate from shader tooling.

See [`docs/SHADER_ARCHITECTURE.md`](docs/SHADER_ARCHITECTURE.md) for the current shader architecture.

## Current 0.1.0 scope

The first release is focused on being sufficient to build a small real-time textured 3D renderer on either OpenGL or Vulkan without application rendering code touching the native graphics APIs.

The current scope includes:

- OpenGL and Vulkan backends
- presentation and offscreen render targets
- vertex, index, and uniform buffers
- 2D textures
- samplers
- GLSL and SPIR-V shader representations
- typed resource bindings
- graphics pipeline state
- vertex and index input
- non-indexed and indexed drawing
- instanced drawing
- triangle, line, and point topology
- face culling
- depth testing and writing
- source-alpha blending
- viewport and scissor state
- explicit resource transitions
- texture-to-texture copies
- ordered dynamic buffer writes
- command recording and submission
- deterministic GPU resource lifetimes

Some important 0.1.0 work is still in progress, including broader validation, additional real-world rendering workloads, and release packaging.

The detailed release plan is available in [`docs/0.1.0-RELEASE-PLAN.md`](docs/0.1.0-RELEASE-PLAN.md).

## Requirements

Development currently requires:

- JDK 25+
- Maven 3.9+
- LWJGL 3.4.3
- OpenGL 4.3+ for the OpenGL backend
- Vulkan 1.3 for the Vulkan backend

The portable core module itself does not depend on LWJGL.
Backend implementations use Java's Foreign Function & Memory API for Drakon-owned native memory while LWJGL remains the native graphics binding layer.

## Building

Build and verify the complete reactor from the repository root:

```bash
mvn verify
```

The project is compiled with:

```text
--release 25
-Xlint:all
-Werror
```

Public core Javadocs are also checked with strict doclint.

To install the current snapshot artifacts into your local Maven repository:

```bash
mvn install
```

## Development backend demo

The repository contains a development-only `spike` module used to exercise the real OpenGL and Vulkan implementations.

After installing the reactor:

```bash
mvn -f spike/pom.xml exec:java -Dexec.args=opengl
```

or:

```bash
mvn -f spike/pom.xml exec:java -Dexec.args=vulkan
```

The spike owns its window-system integration and is intentionally separate from the production graphics modules.

## Project status

`drakon-graphics` has not reached 0.1.0 yet.

The current focus is correctness and establishing a compact public API that can support the same real renderer across OpenGL and Vulkan before expanding the feature surface.

Features such as compute shaders, storage resources, indirect rendering, bindless rendering, render graphs, and more advanced texture types are deliberately outside the initial 0.1.0 scope.
