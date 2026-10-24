#version 330

#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 pixelScale;

layout(location = 0) out vec4 fragColor;

void main() {
    // 天空离屏贴图就是主渲染目标按行原样拷贝出来的，而 gl_FragCoord 的行零点和后端存储行零点
    // 是同一个（GL/Vulkan 在底、D3D12 在顶），所以这里按屏幕像素直接采样，三个后端都不用翻 Y。
    vec4 color = texture(Sampler0, gl_FragCoord.xy * pixelScale);
    fragColor = color * ColorModulator;
}
