#version 150

// ⚠️ 1.20.1 铁律:GLSL 版本是 1.50,<b>不支持</b> layout(location=N) 语法(那是
// 3.30+)。attribute 位置必须与 DefaultVertexFormat.NEW_ENTITY 的元素顺序一致,
// 靠 shader json 的 "attributes" 字段让 ShaderInstance 调用 glBindAttribLocation
// (Position=0, Color=1, UV0=2, UV1=3, UV2=4, Normal=5)。
// 这里<b>必须声明全部 6 个 in 变量</b>(含 UV1),与 vanilla vsh
// (rendertype_entity_translucent.vsh)完全一致 —— 缺任一变量会导致
// GLSL 链接器按声明顺序分配 location,与 VAO 布局(按 VertexFormat 元素顺序)
// 错位 → UV0 读到错误数据 → 物品贴图 alpha 遮罩失效 → 描边变实心方形。
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in vec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    texCoord0 = UV0;
}
