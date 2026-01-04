#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require

precision highp float;

// 接收由 Vertex Shader 变换好的正确纹理坐标
in vec2 vTexCoord;
out vec4 outColor;

uniform samplerExternalOES uTextureRGB;
uniform sampler2D uTextureDepth;

uniform vec2 u_resolution;
uniform float u_density;
uniform float u_dotSizeFactor;
uniform float u_distortionFactor;
uniform vec4 u_foregroundColor;
uniform vec4 u_backgroundColor;

void main() {
    // 1. 深度读取 (直接使用 vTexCoord)
    float depth = texture(uTextureDepth, vTexCoord).r;

    // 2. 畸变处理 (Distortion)
    // 在纹理空间进行畸变，可能导致边缘拉伸，但在 OES 模式下这是最安全的
    vec2 center = vec2(0.5, 0.5);
    vec2 fromCenter = vTexCoord - center;
    vec2 distortedUV = vTexCoord + fromCenter * (1.0 - depth) * u_distortionFactor;

    // 3. 网格计算 (Grid Logic)
    // 直接基于纹理坐标划分网格。
    // 注意：如果相机纹理旋转了90度，网格也会跟着旋转，但这能保证画面有内容。
    vec2 gridScale = vec2(u_density, u_density);

    // 简单的长宽比修正，防止点变成椭圆
    float aspectRatio = u_resolution.x / u_resolution.y;
    if (aspectRatio > 1.0) {
        gridScale.y /= aspectRatio;
    } else {
        gridScale.x *= aspectRatio;
    }

    vec2 gridUV = distortedUV * gridScale;
    vec2 gridCellCenter = floor(gridUV) + 0.5;
    vec2 sampleCoord = gridCellCenter / gridScale;

    // 4. 边界检查 (防止采样溢出导致花屏)
    if (sampleCoord.x < 0.0 || sampleCoord.x > 1.0 || sampleCoord.y < 0.0 || sampleCoord.y > 1.0) {
        outColor = u_backgroundColor;
        return;
    }

    // 5. 采样颜色 (关键步骤)
    // 直接使用算好的坐标采样，不再乘矩阵
    vec3 originalColor = texture(uTextureRGB, sampleCoord).rgb;

    // 计算亮度 (Luma)
    float luma = dot(originalColor, vec3(0.299, 0.587, 0.114));

    // 6. 确定点的大小
    float dotSize = (1.0 - luma) * u_dotSizeFactor;
    // 增加非线性曲线，让对比度更明显
    dotSize = 0.05 + pow(dotSize, 3.0) * 0.95;

    // 7. 绘制圆点
    vec2 cellUV = fract(gridUV) - 0.5;
    // 修正单元格内的 UV 以保持圆形
    if (aspectRatio > 1.0) {
        cellUV.y *= aspectRatio;
    } else {
        cellUV.x /= aspectRatio;
    }

    float dist = length(cellUV) * 2.0;
    float aa = 1.0 / u_density; //抗锯齿边缘宽度

    float inShape = 1.0 - smoothstep(dotSize - aa, dotSize + aa, dist);

    outColor = mix(u_backgroundColor, u_foregroundColor, inShape);
}