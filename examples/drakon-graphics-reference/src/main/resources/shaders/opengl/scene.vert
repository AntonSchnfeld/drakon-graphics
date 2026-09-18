#version 430

layout(location = 0) in vec3 position;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec4 model0;
layout(location = 3) in vec4 model1;
layout(location = 4) in vec4 model2;
layout(location = 5) in vec4 model3;
layout(location = 6) in vec4 tint;

layout(std140, binding = 0) uniform CameraData {
    mat4 viewProjection;
} camera;

layout(location = 0) out vec2 interpolatedUv;
layout(location = 1) out vec4 interpolatedTint;

void main() {
    mat4 model = mat4(model0, model1, model2, model3);
    vec4 clip = camera.viewProjection * model * vec4(position, 1.0);

    // The shared JOML projection uses Vulkan's [0, 1] depth range.
    clip.z = 2.0 * clip.z - clip.w;
    gl_Position = clip;
    interpolatedUv = uv;
    interpolatedTint = tint;
}
