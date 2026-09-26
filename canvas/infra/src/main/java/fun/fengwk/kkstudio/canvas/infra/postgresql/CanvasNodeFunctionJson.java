package fun.fengwk.kkstudio.canvas.infra.postgresql;

import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code canvas_node.function} JSONB 列与 {@link CanvasFunction} 的 base shape {@code {name,args}}
 * 编解码。
 *
 * <p>列的检查约束要求 name 是非空白字符串、args 是 object；这里使用 Core 的严格 JSON 模型，使读写共用同一套语义与转义规则， 不引入第二份 args 表示。
 */
final class CanvasNodeFunctionJson {

  private CanvasNodeFunctionJson() {}

  /** 序列化为持久化文本；{@code null} 函数表示为 SQL NULL。 */
  static String encode(CanvasFunction function) {
    if (function == null) {
      return null;
    }
    Map<String, CanvasJson> values = new LinkedHashMap<>();
    values.put("name", new CanvasJson.JsonText(function.name()));
    values.put("args", function.args());
    return new CanvasJson.JsonObject(values).write();
  }

  /** 解析持久化文本；列内容不符合 base shape 时抛 {@link CanvasValidationException}。 */
  static CanvasFunction decode(String functionJson) {
    if (functionJson == null) {
      return null;
    }
    CanvasJson.JsonObject shape;
    try {
      shape = CanvasJson.parseObject(functionJson);
    } catch (IllegalArgumentException error) {
      throw new CanvasValidationException(
          "node function must be strict JSON: " + error.getMessage());
    }
    CanvasJson name = shape.values().get("name");
    CanvasJson args = shape.values().get("args");
    if (!(name instanceof CanvasJson.JsonText nameText)) {
      throw new CanvasValidationException("node function must contain a string name");
    }
    if (!(args instanceof CanvasJson.JsonObject argsObject)) {
      throw new CanvasValidationException("node function must contain an object args");
    }
    return new CanvasFunction(nameText.value(), argsObject);
  }
}
