package fun.fengwk.kkstudio.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** {@link BoundedJsonWriter} 的有界 UTF-8 编码、临界字节判定与提前中止测试。 */
class BoundedJsonWriterTest {

  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

  /** 验证未超限返回与全量序列化完全一致的文本，超限一字节返回 null。 */
  @Test
  void writeReturnsExactTextWithinLimitAndNullAbove() {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("msg", "hello");
    String fullJson = "{\"msg\":\"hello\"}";
    int exactBytes = fullJson.getBytes(StandardCharsets.UTF_8).length;

    assertEquals(fullJson, BoundedJsonWriter.write(node, exactBytes));
    assertEquals(fullJson, BoundedJsonWriter.write(node, exactBytes + 10));
    assertNull(BoundedJsonWriter.write(node, exactBytes - 1));
  }

  /** 验证多字节 UTF-8 字符按实际输出字节数精确计算。 */
  @Test
  void countsMultiByteUtf8Accurately() {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("text", "你好世界");
    String written = BoundedJsonWriter.write(node, Integer.MAX_VALUE);
    int exactBytes = written.getBytes(StandardCharsets.UTF_8).length;

    assertEquals(written, BoundedJsonWriter.write(node, exactBytes));
    assertNull(BoundedJsonWriter.write(node, exactBytes - 1));
  }

  /** emoji（代理对，4 字节 UTF-8）同样按字节边界判定，write 与 fits 在临界点一致。 */
  @Test
  void countsEmojiBytesAtExactBoundary() {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("emoji", "😀🚀");
    String written = BoundedJsonWriter.write(node, Integer.MAX_VALUE);
    int exactBytes = written.getBytes(StandardCharsets.UTF_8).length;

    assertEquals(written, BoundedJsonWriter.write(node, exactBytes));
    assertNull(BoundedJsonWriter.write(node, exactBytes - 1));
    assertTrue(BoundedJsonWriter.fits(node, exactBytes));
    assertFalse(BoundedJsonWriter.fits(node, exactBytes - 1));
  }

  /** 无保留判定与 write 使用完全相同的 UTF-8 边界，但不需要物化最终字符串。 */
  @Test
  void fitsUsesTheSameUtf8BoundaryWithoutRetainingOutput() {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("text", "你好");
    int exactBytes =
        BoundedJsonWriter.write(node, Integer.MAX_VALUE).getBytes(StandardCharsets.UTF_8).length;

    assertTrue(BoundedJsonWriter.fits(node, exactBytes));
    assertFalse(BoundedJsonWriter.fits(node, exactBytes - 1));
  }

  /** 验证超大 JSON 在输出流超限时提前中止，返回 null 而不耗尽内存。 */
  @Test
  void abortsEarlyOnHugeNode() {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("big", "x".repeat(10 * 1024 * 1024));

    assertNull(BoundedJsonWriter.write(node, 1024));
  }

  /**
   * 超限识别必须沿 cause 链只认有界输出流自身的 {@link BoundedOutputStream.LimitExceededException}；其它序列化/IO 失败不得被
   * 误判为超限，否则会把真实编码错误静默变成 null。
   */
  @Test
  void isOverLimitOnlyMatchesOwnLimitExceededCause() throws Exception {
    BoundedOutputStream.LimitExceededException limit = limitExceeded();
    assertTrue(BoundedJsonWriter.isOverLimit(limit));
    assertTrue(BoundedJsonWriter.isOverLimit(new IllegalStateException("wrapper", limit)));

    assertFalse(BoundedJsonWriter.isOverLimit(new IOException("generic io failure")));
    assertFalse(BoundedJsonWriter.isOverLimit(new IllegalStateException(new IOException("io"))));
    assertFalse(BoundedJsonWriter.isOverLimit(new IllegalStateException("no cause")));
  }

  /** 验证非法参数校验：maxBytes 非正数抛 IllegalArgumentException，node 为 null 抛 NullPointerException。 */
  @Test
  void validatesArguments() {
    JsonNode node = OBJECT_MAPPER.createObjectNode();

    assertThrows(IllegalArgumentException.class, () -> BoundedJsonWriter.write(node, 0));
    assertThrows(IllegalArgumentException.class, () -> BoundedJsonWriter.write(node, -1));
    assertThrows(NullPointerException.class, () -> BoundedJsonWriter.write(null, 1024));
    assertThrows(IllegalArgumentException.class, () -> BoundedJsonWriter.fits(node, 0));
    assertThrows(NullPointerException.class, () -> BoundedJsonWriter.fits(null, 1024));
  }

  /** 使用真实有界输出流产生一个超限中止异常，供超限识别断言使用。 */
  private static BoundedOutputStream.LimitExceededException limitExceeded() throws IOException {
    BoundedOutputStream out = new BoundedOutputStream(1);
    try {
      out.write(new byte[] {1, 2});
    } catch (BoundedOutputStream.LimitExceededException error) {
      return error;
    }
    throw new AssertionError("expected BoundedOutputStream to abort over limit");
  }

  /**
   * 通用序列化失败（非超限）必须抛 IllegalArgumentException 而不是被误判为超限返回 null，并保留原始 Jackson cause； 超限识别只认 {@link
   * BoundedOutputStream.LimitExceededException}。
   */
  @Test
  void serializationFailureSurfacesAsIllegalArgumentNotOverLimitNull() {
    JsonNode node = JsonNodeFactory.instance.pojoNode(new ThrowingValue());

    IllegalArgumentException writeError =
        assertThrows(IllegalArgumentException.class, () -> BoundedJsonWriter.write(node, 1024));
    assertEquals("cannot encode JSON", writeError.getMessage());
    assertTrue(writeError.getCause() instanceof JacksonException);
    assertFalse(BoundedJsonWriter.isOverLimit(writeError));

    assertThrows(IllegalArgumentException.class, () -> BoundedJsonWriter.fits(node, 1024));
  }

  /** 让序列化器直接抛出 JacksonException 的真实 POJO，用于验证通用失败不会被当成超限。 */
  @JsonSerialize(using = ThrowingSerializer.class)
  private static final class ThrowingValue {}

  private static final class ThrowingSerializer extends ValueSerializer<ThrowingValue> {
    @Override
    public void serialize(
        ThrowingValue value, JsonGenerator generator, SerializationContext context) {
      throw DatabindException.from(generator, "serializer failure");
    }
  }
}
