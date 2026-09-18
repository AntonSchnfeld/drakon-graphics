# drakon-graphics 0.1 reference application

This non-published module is a small consumer of the public Drakon API. It renders
three independently animated textured cubes and a moving translucent panel with a
slowly orbiting perspective camera. The scene is drawn into resize-owned RGBA8 and
D32 offscreen attachments, then sampled by a fullscreen pass that adds restrained
color grading and a vignette before normal presentation.

The same `ReferenceRenderer` runs on OpenGL and Vulkan.

## Run

From the repository root, install the reactor artifacts once:

```bash
mvn install -DskipTests
```

Run either interactive backend:

```bash
mvn -f examples/drakon-graphics-reference/pom.xml exec:java -Dexec.args=opengl
mvn -f examples/drakon-graphics-reference/pom.xml exec:java -Dexec.args=vulkan
```

For a testable 240-frame run that closes automatically:

```bash
mvn -f examples/drakon-graphics-reference/pom.xml exec:java '-Dexec.args=opengl finite'
mvn -f examples/drakon-graphics-reference/pom.xml exec:java '-Dexec.args=vulkan finite'
```

The single-quoted argument form works in PowerShell and POSIX shells. On a shell
that handles quoting differently, pass the two words as the value of
`-Dexec.args` by that shell's normal rules.

## Reading the example

Start with these classes:

- `Main` selects the backend, owns the frame loop, skips rendering while the raw
  native framebuffer is zero, resizes the renderer, and calls `present()`.
- `GraphicsSession` contains GLFW and backend-specific device/presentation setup.
- `ReferenceRenderer` is the backend-neutral Drakon rendering algorithm and owns
  the portable GPU resources it creates.
- `ReferenceScene` uses JOML for perspective, view, model, and animation transforms,
  then packs the changing matrices into persistent buffers written each frame.

`SceneGeometry` keeps generated vertex/index/texture data away from the application
flow. `ShaderResources` loads named shader resources and compiles only the Vulkan
GLSL to SPIR-V with example-scoped Shaderc plumbing.

Shader source lives under:

```text
src/main/resources/shaders/
  opengl/{scene,post}.{vert,frag}
  vulkan/{scene,post}.{vert,frag}
```

JOML and Shaderc are dependencies of this example module only. Neither is added to
the public Drakon artifacts. The module's `verify` phase also runs a source-level
guard that rejects OpenGL, Vulkan, LWJGL, or native graphics calls in
`ReferenceRenderer`.

## Resize and minimize behavior

The application keeps processing events while a minimized window reports a raw
zero-sized framebuffer and does not intentionally begin a frame in that state.
The stable presentation `RenderTarget` remains owned by the backend. A resize
replaces only the application-owned offscreen attachments; if the presentation
format changes, only the fullscreen graphics state is rebuilt. The graphics device
is not recreated.
