package fun.fengwk.kkstudio.canvas.dto;

import tools.jackson.databind.JsonNode;

/**
 * ResourceNode 上可选的资源生产配置：函数名加插件声明的 args。
 *
 * <p>args 是任意 JSON object，服务端按保留引用形状 {@code {"type":"resource","nodeId":...,"index":0}}
 * 遍历识别引用连线；它也是 Function 语义组的编辑基线。Function 目录不假设 model、prompt 或 provider，裁剪、 拼接等函数可以只有几何参数。
 */
public record CanvasFunctionDTO(String name, JsonNode args) {}
