package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

/** JSON 工具内容；原始文本的 UTF-8 字节数在树解析前受 {@link #MAX_JSON_UTF8_BYTES} 上限约束。 */
public record JsonToolContent(String json) implements ToolContent {

  /** JSON 原始文本的 UTF-8 字节上限：1 MiB，保证任何树优先解析只面对有界输入。 */
  public static final int MAX_JSON_UTF8_BYTES = 1024 * 1024;

  public JsonToolContent {
    json = requireBoundedValidJson(json);
  }

  private static String requireBoundedValidJson(String json) {
    if (ResourceRef.utf8LengthUpTo(json, "json", MAX_JSON_UTF8_BYTES) > MAX_JSON_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "json must not exceed " + MAX_JSON_UTF8_BYTES + " UTF-8 bytes");
    }
    return ToolArgumentsValidator.requireValidJson(json);
  }
}
