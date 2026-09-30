package fun.fengwk.kkstudio.harness.common.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/**
 * 通用有界 JsonNode UTF-8 编码器。
 *
 * <p>将任意 {@link JsonNode} 序列化为 JSON 文本并施加 UTF-8 字节上限：超过 {@code maxBytes} 字节即在中止点停止并返回 {@code
 * null}（不物化完整输出）；未超限时返回完整 JSON 文本。上限判定由共享的 {@link BoundedOutputStream} 承担。
 */
public final class BoundedJsonWriter {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    } catch (BoundedOutputStream.LimitExceededException error) {
      return null;
    } catch (IOException exception) {
      throw new IllegalArgumentException("cannot encode JSON", exception);
    }
    return out;
  }
}
