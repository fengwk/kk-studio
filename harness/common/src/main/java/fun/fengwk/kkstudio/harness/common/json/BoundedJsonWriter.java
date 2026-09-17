package fun.fengwk.kkstudio.harness.common.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 通用有界 JsonNode UTF-8 编码器。
 *
 * <p>将任意 {@link JsonNode} 序列化为 JSON 文本并施加 UTF-8 字节上限：超过 {@code maxBytes} 字节即在中止点停止并返回 {@code
 * null}（不物化完整输出）；未超限时返回完整 JSON 文本。
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
    BoundedUtf8OutputStream out = serialize(node, maxBytes, true);
    return out == null ? null : out.toStringUtf8();
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

  private static BoundedUtf8OutputStream serialize(
      JsonNode node, int maxBytes, boolean retainBytes) {
    Objects.requireNonNull(node, "node");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    BoundedUtf8OutputStream out = new BoundedUtf8OutputStream(maxBytes, retainBytes);
    try {
      OBJECT_MAPPER.writeValue(out, node);
    } catch (Utf8LimitExceededException error) {
      return null;
    } catch (IOException exception) {
      throw new IllegalArgumentException("cannot encode JSON", exception);
    }
    return out;
  }

  /** 编码超过上限时由 bounded 输出流抛出（IOException 使 Jackson 不做二次包装）。 */
  private static final class Utf8LimitExceededException extends IOException {
    private Utf8LimitExceededException() {}
  }

  /** 有界 UTF-8 输出流：累计超过 maxBytes 即抛出中止异常，不物化完整内容；正常完成时按 UTF-8 还原文本。 */
  private static final class BoundedUtf8OutputStream extends OutputStream {
    private final int maxBytes;
    private final ByteArrayOutputStream bytes;
    private int count;

    private BoundedUtf8OutputStream(int maxBytes, boolean retainBytes) {
      this.maxBytes = maxBytes;
      this.bytes = retainBytes ? new ByteArrayOutputStream() : null;
    }

    @Override
    public void write(int b) throws IOException {
      check(1);
      if (bytes != null) {
        bytes.write(b);
      }
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      check(length);
      if (bytes != null) {
        bytes.write(buffer, offset, length);
      }
    }

    private void check(int length) throws IOException {
      if (length > maxBytes - count) {
        throw new Utf8LimitExceededException();
      }
      count += length;
    }

    private String toStringUtf8() {
      if (bytes == null) {
        throw new IllegalStateException("serialized bytes were not retained");
      }
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }
}
