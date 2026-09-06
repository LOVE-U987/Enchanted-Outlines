#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float OutlineAlphaBoost;
uniform float OutlineCutout;
uniform float OutlineBlend;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    // 描边核心:贴图 alpha 提供形状遮罩,alpha 经 vertexColor.a 与 boost 强化;
    // OutlineAlphaBoost > 1 时把边缘渐变 alpha 抬升为实心,让手持描边不透明
    float a = clamp(texel.a * vertexColor.a * OutlineAlphaBoost, 0.0, 1.0);
    // OutlineCutout > 0.5:硬切不透明模式(物品展示框等背景物体)。
    // 半透明混合下边缘渐变像素(alpha<1)即使 boost 后仍透出背景 → 硬切成实心。
    if (OutlineCutout > 0.5) {
        a = (a > 0.05) ? 1.0 : 0.0;
    }
    // 颜色来源:vertexColor.rgb 携带描边纯色(3D 几何壳路径)。OutlineBlend>0 时
    // 把 texel.rgb 混入 mask:
    //   - 扁平"形状纹理"把描边色烘焙进贴图 RGB(顶点色为白)→ vertex×texel = 描边色;
    //   - 盔甲混色开(armorPixelColorGlint=true)→ 描边色 × 纹理色混合。
    vec3 mask = mix(vec3(1.0), texel.rgb, OutlineBlend);
    fragColor = vec4(vertexColor.rgb * mask, a) * ColorModulator;
}
