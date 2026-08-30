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
    Objects.requireNonNull(node, "node");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    BoundedUtf8OutputStream out = new BoundedUtf8OutputStream(maxBytes);
    try {
      OBJECT_MAPPER.writeValue(out, node);
    } catch (Utf8LimitExceededException error) {
      return null;
    } catch (IOException exception) {
      throw new IllegalArgumentException("cannot encode JSON", exception);
    }
    return out.toStringUtf8();
  }

  /** 编码超过上限时由 bounded 输出流抛出（IOException 使 Jackson 不做二次包装）。 */
  private static final class Utf8LimitExceededException extends IOException {
    private Utf8LimitExceededException() {}
  }

  /** 有界 UTF-8 输出流：累计超过 maxBytes 即抛出中止异常，不物化完整内容；正常完成时按 UTF-8 还原文本。 */
  private static final class BoundedUtf8OutputStream extends OutputStream {
    private final int maxBytes;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int count;

    private BoundedUtf8OutputStream(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    public void write(int b) throws IOException {
      check(1);
      bytes.write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      check(length);
      bytes.write(buffer, offset, length);
    }

    private void check(int length) throws IOException {
      if (length > maxBytes - count) {
        throw new Utf8LimitExceededException();
      }
      count += length;
    }

    private String toStringUtf8() {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }
}
