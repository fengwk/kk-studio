package fun.fengwk.kkstudio.common.json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * 通用有界 JsonNode UTF-8 编码器。
 *
 * <p>将任意 {@link JsonNode} 序列化为 JSON 文本并施加 UTF-8 字节上限：超过 {@code maxBytes} 字节即在中止点停止并返回 {@code
 * null}（不物化完整输出）；未超限时返回完整 JSON 文本。上限判定由共享的 {@link BoundedOutputStream} 承担。
 *
 * <p>Jackson 会把 {@link BoundedOutputStream} 抛出的 {@link BoundedOutputStream.LimitExceededException}
 * 包装成 {@link JacksonException}，因此超限识别必须沿 cause 链定位本类专属异常， 不能把任意序列化/IO 失败都当作超限。
 */
public final class BoundedJsonWriter {

  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

  private BoundedJsonWriter() {}

  /**
   * 将 {@link JsonNode} 编码为有界 UTF-8 JSON 文本。
   *
   * @param node 待序列化节点，不能为 null
   * @param maxBytes 正数 UTF-8 字节上限
   * @return 未超过上限时返回完整 JSON 文本；超过上限时返回 {@code null}
   * @throws IllegalArgumentException maxBytes 非正数或序列化失败时
   */
  public static String write(JsonNode node, int maxBytes) {
    BoundedOutputStream out = serialize(node, maxBytes, true);
    return out == null ? null : out.toUtf8String();
  }

  /**
   * 判断 {@link JsonNode} 的 UTF-8 JSON 输出是否不超过上限，不保留序列化字节。
   *
   * @param node 待序列化节点，不能为 null
   * @param maxBytes 正数 UTF-8 字节上限
   */
  public static boolean fits(JsonNode node, int maxBytes) {
    return serialize(node, maxBytes, false) != null;
  }

  private static BoundedOutputStream serialize(JsonNode node, int maxBytes, boolean retainBytes) {
    Objects.requireNonNull(node, "node");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    BoundedOutputStream out = new BoundedOutputStream(maxBytes, retainBytes);
    try {
      OBJECT_MAPPER.writeValue(out, node);
    } catch (JacksonException error) {
      if (isOverLimit(error)) {
        return null;
      }
      throw new IllegalArgumentException("cannot encode JSON", error);
    }
    return out;
  }

  /** 仅当 cause 链中出现有界输出流自身的超限中止时判定为超限；其它序列化/IO 失败不视为超限。 */
  static boolean isOverLimit(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof BoundedOutputStream.LimitExceededException) {
        return true;
      }
    }
    return false;
  }
}
