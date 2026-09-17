package fun.fengwk.kkstudio.harness.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/** {@link BoundedJsonWriter} 的有界 UTF-8 编码、临界字节判定与提前中止测试。 */
class BoundedJsonWriterTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
}
