package fun.fengwk.kkstudio.harness.environment.protocol;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.resource.ResourceRef;

/**
 * opaque JSON 文本的应用层校验。
 *
 * <p>JSON 在协议里是原始 string，不映射为 Struct。这里用现有 {@link JsonValues} 严格读取一次，拒绝非法语法、 重复键与尾随 token，再检查 UTF-8
 * 字节上限；返回的始终是原始文本，绝不把 AST 重新序列化，因此 {@code 1e-7}、高精度小数与 {@code -0} 可以逐字保留。
 */
final class ProtocolJson {

  private ProtocolJson() {}

  /** 要求文本是合法 JSON object，并保留原始文本。 */
  static String requireObject(String text, String name, int maxUtf8Bytes) {
    requirePresent(text, name);
    requireBoundedUtf8(text, name, maxUtf8Bytes);
    try {
      JsonValues.requireJsonObject(text, name);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
    return text;
  }

  /** 要求文本是合法 JSON value（包含 JSON null），并保留原始文本。 */
  static String requireValue(String text, String name, int maxUtf8Bytes) {
    requirePresent(text, name);
    requireBoundedUtf8(text, name, maxUtf8Bytes);
    try {
      JsonValues.requireValidJson(text);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
    return text;
  }

  private static void requirePresent(String text, String name) {
    if (text == null || text.isBlank()) {
      throw new ControlProtocolException(name + " is required");
    }
  }

  private static void requireBoundedUtf8(String text, String name, int maxUtf8Bytes) {
    int length;
    try {
      length = ResourceRef.utf8LengthUpTo(text, name, maxUtf8Bytes);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
    if (length > maxUtf8Bytes) {
      throw new ControlProtocolException(name + " exceeds " + maxUtf8Bytes + " UTF-8 bytes");
    }
  }
}
