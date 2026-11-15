package fun.fengwk.kkstudio.common.json;

import tools.jackson.databind.JsonNode;

/**
 * Tool 历史 action 渲染器读取 canonical arguments 的只读工具：把 arguments 文本解析为 JSON 对象，并按语义字段名取用。
 *
 * <p>渲染器只做自然语言描述，绝不改变 durable 事实，因此本类对所有畸形输入都返回 {@code null} 而不是抛出：调用方据此回退到通用描述。 参数本身仍由 durable
 * ToolCall 完整保留。
 */
public final class ToolArguments {

  private ToolArguments() {}

  /** 解析 arguments 文本为 JSON 对象；null / 空白 / 非法 JSON / 非对象一律返回 null。 */
  public static JsonNode parse(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JsonValues.readTree(argumentsJson);
      return node != null && node.isObject() ? node : null;
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  /** 读取非空文本字段并保留原文；缺失 / null / 非文本 / 空白返回 null。 */
  public static String text(JsonNode arguments, String field) {
    if (arguments == null) {
      return null;
    }
    JsonNode value = arguments.get(field);
    if (value == null || !value.isString()) {
      return null;
    }
    String text = value.stringValue();
    return text.isBlank() ? null : text;
  }

  /** 读取布尔字段；非布尔或缺失返回 false。 */
  public static boolean flag(JsonNode arguments, String field) {
    if (arguments == null) {
      return false;
    }
    JsonNode value = arguments.get(field);
    return value != null && value.isBoolean() && value.booleanValue();
  }
}
