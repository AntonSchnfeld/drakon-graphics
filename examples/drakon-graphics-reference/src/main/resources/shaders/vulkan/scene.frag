#version 450

layout(set = 0, binding = 1) uniform sampler2D sceneTexture;
layout(location = 0) in vec2 interpolatedUv;
layout(location = 1) in vec4 interpolatedTint;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(sceneTexture, interpolatedUv) * interpolatedTint;
}
