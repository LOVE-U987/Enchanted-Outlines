#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float OutlineAlphaBoost;
uniform float OutlineCutout;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    // 描边核心:贴图只提供 alpha 遮罩(物品形状),RGB 一律用纯描边色
    // OutlineAlphaBoost > 1 时把贴图边缘的渐变 alpha 抬升为实心,让手持描边不透明
    float a = clamp(texel.a * vertexColor.a * OutlineAlphaBoost, 0.0, 1.0);
    // OutlineCutout > 0.5:硬切不透明模式(物品展示框等背景物体)。
    // 半透明混合下贴图边缘渐变像素(alpha<1)即使 boost 后仍透出背景,在明亮场景
    // 背景下几乎不可见 → 直接把非透明像素切成 alpha=1.0 的不透明纯色。
    if (OutlineCutout > 0.5) {
        a = (a > 0.05) ? 1.0 : 0.0;
    }
    fragColor = vec4(vertexColor.rgb, a) * ColorModulator;
}
