#version 330

#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// 顶点属性名必须与顶点格式里的元素名一致（DefaultVertexFormat.POSITION_TEX 的元素名是
// "Position" / "UV0"）：原版 PipelineBuilder 是按名字把着色器输入和顶点缓冲元素对上号的，
// 名字不匹配会直接报 "attrib (...) does not have a matching vertex buffer element"。
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

// 这里 UV0 不当地址用，而是当作「屏幕像素 -> 贴图坐标」的比例（1/宽, 1/高）传进来，
// 片元着色器再乘 gl_FragCoord 得到与主渲染目标逐像素对齐的采样坐标。
layout(location = 0) out vec2 pixelScale;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    pixelScale = UV0;
}