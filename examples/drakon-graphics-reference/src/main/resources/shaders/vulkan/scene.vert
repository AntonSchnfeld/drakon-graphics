#version 450

layout(location = 0) in vec3 position;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec4 model0;
layout(location = 3) in vec4 model1;
layout(location = 4) in vec4 model2;
layout(location = 5) in vec4 model3;
layout(location = 6) in vec4 tint;

layout(set = 0, binding = 0, std140) uniform CameraData {
    mat4 viewProjection;
} camera;

layout(location = 0) out vec2 interpolatedUv;
layout(location = 1) out vec4 interpolatedTint;

void main() {
    mat4 model = mat4(model0, model1, model2, model3);
    gl_Position = camera.viewProjection * model * vec4(position, 1.0);
    interpolatedUv = uv;
    interpolatedTint = tint;
}
