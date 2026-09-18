#version 430

layout(binding = 1) uniform sampler2D sceneTexture;
layout(location = 0) in vec2 interpolatedUv;
layout(location = 1) in vec4 interpolatedTint;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 textureUv = vec2(interpolatedUv.x, 1.0 - interpolatedUv.y);
    outColor = texture(sceneTexture, textureUv) * interpolatedTint;
}
