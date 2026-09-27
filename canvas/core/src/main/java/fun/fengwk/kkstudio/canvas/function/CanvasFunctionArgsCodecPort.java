package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;

/** Function args 的严格解析与 canonical 编码端口。 */
public interface CanvasFunctionArgsCodecPort {

  /** 严格解析 args JSON，按定义 schema 校验并补齐默认值；任何未知字段或类型错误都 fail closed。 */
  JsonObject decode(String argsJson, CanvasFunctionDefinition definition);

  /** canonical 编码已校验的 args。 */
  String encode(JsonObject args);
}
