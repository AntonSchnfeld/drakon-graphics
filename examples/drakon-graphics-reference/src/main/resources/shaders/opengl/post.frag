#version 430

layout(binding = 0) uniform sampler2D offscreenColor;
layout(location = 0) in vec2 interpolatedUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 sampleUv = vec2(interpolatedUv.x, 1.0 - interpolatedUv.y);
    vec3 scene = texture(offscreenColor, sampleUv).rgb;
    vec2 centered = interpolatedUv * 2.0 - 1.0;
    float vignette = clamp(1.04 - 0.24 * dot(centered, centered), 0.72, 1.0);
    vec3 graded = pow(max(scene, vec3(0.0)), vec3(0.92));
    outColor = vec4(graded * vec3(1.02, 0.99, 1.06) * vignette, 1.0);
}
