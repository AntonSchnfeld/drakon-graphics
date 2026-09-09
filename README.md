# drakon-graphics — LWJGL backend spike

This is the first real-backend pressure test of the `drakon-graphics` API after Iterations 1-6.

This repository is the source of truth for `drakon-graphics`. It is a Maven reactor of independent modules; no module inherits from the root aggregator:

```text
drakon-graphics-build (aggregator only)
├── drakon-graphics
├── backends/drakon-graphics-opengl
├── backends/drakon-graphics-vulkan
└── spike (development-only smoke harness)
```

The public Maven artifacts are:

- `io.github.antonschnfeld:drakon-graphics`
- `io.github.antonschnfeld:drakon-graphics-opengl`
- `io.github.antonschnfeld:drakon-graphics-vulkan`

Java packages use `io.github.antonschnfeld.drakon.graphics`. The aggregator and spike are build/development infrastructure and are not deployed as public artifacts.

## Requirements

- JDK 21+
- Maven 3.9+
- current GPU drivers
- OpenGL 4.3+ for the OpenGL smoke path
- Vulkan 1.3-capable driver/runtime for the Vulkan smoke path

LWJGL is pinned to **3.4.3**.

No JOML dependency is currently necessary.

## 1. Compile and validate the complete Maven reactor

From the project root:

```bash
mvn verify
```

The compiler is configured with:

```text
--release 21
-Xlint:all
-Werror
```

The core module also runs strict Javadoc/doclint and the existing A-J probe + API contract suite during `verify`.

## 2. Run the OpenGL smoke test

Install the reactor artifacts once:

```bash
mvn install
```

Then:

```bash
mvn -f spike/pom.xml exec:java -Dexec.args=opengl
```

Or on Windows:

```text
run-opengl.cmd
```

Expected result: an 800x500 GLFW window rendering a colored triangle through the portable Drakon render API.

## 3. Run the Vulkan smoke test

```bash
mvn -f spike/pom.xml exec:java -Dexec.args=vulkan
```

Or on Windows:

```text
run-vulkan.cmd
```

The spike harness uses Shaderc to compile its tiny test GLSL into SPIR-V. Shaderc is deliberately not a dependency of the Vulkan backend itself.

Expected result: the same colored triangle through the Vulkan backend and the portable `RenderTarget` + `GraphicsDevice.present(...)` contract.

## Native classifiers

The independent `spike/pom.xml` activates LWJGL runtime-native classifiers for common desktop architectures:

- Windows x64 -> `natives-windows`
- Windows ARM64 -> `natives-windows-arm64`
- Linux x64 -> `natives-linux`
- Linux ARM64 -> `natives-linux-arm64`
- macOS x64 -> `natives-macos`
- macOS ARM64 -> `natives-macos-arm64`

If Maven does not recognize your JVM's architecture spelling, override manually, for example:

```bash
mvn -Dlwjgl.natives=natives-windows verify
```

## Core-only validation without Maven

The portable API does not require LWJGL. On a Unix-like shell with JDK 21:

```bash
./validate-core.sh
```

This performs strict javac/Javadoc validation and runs the A-J probe + contract suite.

## Important status

The complete Maven reactor now passes `mvn verify` against the real LWJGL dependencies, including strict core Javadoc and the A-J probe + API contract suite. All modules compile for Java 21 with `-Xlint:all -Werror`.

GPU/window smoke tests have not been run as part of the package and Maven-coordinate migration. Successful compilation does not establish backend runtime correctness; the historical preparation status and open backend questions remain in the decision logs.

Do not suppress backend compiler/runtime failures. They are the backend spike's primary evidence.

Read [`BACKEND_SPIKE_FINDINGS.md`](docs/BACKEND_SPIKE_FINDINGS.md) before changing the portable API. It records which changes have already been forced by backend pressure and what questions the real execution is meant to settle.

Other retained decision logs:

- [ITERATION_6_PRESENTATION_FINDINGS.md](docs/ITERATION_6_PRESENTATION_FINDINGS.md)
- [ITERATION_5_SHADER_FINDINGS.md](docs/ITERATION_5_SHADER_FINDINGS.md)
- [SHADER_ARCHITECTURE.md](docs/SHADER_ARCHITECTURE.md)
