package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ProviderProtocolOptions} 契约：null/空白归一化为空 object、canonical 化、严格拒绝与脱敏错误消息。
 *
 * <p>这些断言是「模型配置不得把无限或无法解释的 payload 带进 variant」的唯一执行点，因此既覆盖合法形态的规范化，也覆盖每一类拒绝 路径与错误消息不含 payload
 * 的安全边界。
 */
class ProviderProtocolOptionsTest {

  /** null/空白等价于空 object：不同空白写法必须落到同一 canonical 文本与同一相等语义。 */
  @Test
  void normalizesNullAndBlankToEmptyObject() {
    assertTrue(new ProviderProtocolOptions(null).isEmpty());
    assertTrue(new ProviderProtocolOptions("   ").isEmpty());
    assertTrue(ProviderProtocolOptions.of(null).isEmpty());
    assertEquals(ProviderProtocolOptions.EMPTY, new ProviderProtocolOptions("{}"));
    assertEquals(ProviderProtocolOptions.EMPTY, new ProviderProtocolOptions(" \n { } \t "));
    assertEquals("{}", ProviderProtocolOptions.EMPTY.canonicalJson());
    assertFalse(new ProviderProtocolOptions("{\"a\":1}").isEmpty());
    assertNotEquals(ProviderProtocolOptions.EMPTY, new ProviderProtocolOptions("{\"a\":1}"));
  }

  /** canonical 化去掉无意义的空白与小数尾零，同时保留键顺序和 JSON 数值语义。 */
  @Test
  void canonicalizesWhitespaceAndPreservesKeyOrderAndNumericSemantics() {
    ProviderProtocolOptions options =
        new ProviderProtocolOptions(
            "{ \"thinking\" : { \"budget_tokens\" : 4096 } , \"ratio\" : 1.10 }");

    assertEquals("{\"thinking\":{\"budget_tokens\":4096},\"ratio\":1.1}", options.canonicalJson());
  }

  /** 从已解析的 JSON object 构造时必须保持数值语义：Long 不得被本地 Long 字符串化约定改写。 */
  @Test
  void keepsIntegerAndDoubleValuesNumericWhenBuiltFromParsedObject() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("revision", 1758880000000L);
    raw.put("budget", 4096);
    raw.put("ratio", 0.5d);
    raw.put("nested", Map.of("flags", List.of(true, false)));

    assertEquals(
        "{\"revision\":1758880000000,\"budget\":4096,\"ratio\":0.5,"
            + "\"nested\":{\"flags\":[true,false]}}",
        ProviderProtocolOptions.of(raw).canonicalJson());
  }

  /** 非 object、严格 JSON 语法错误、重复键、trailing token 都明确失败，且消息不回显 payload。 */
  @Test
  void rejectsNonObjectsDuplicatesTrailingTokensAndMalformedJsonWithoutEchoingPayload() {
    for (String notObject : List.of("[]", "\"text\"", "42", "true", "null")) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class, () -> new ProviderProtocolOptions(notObject));
      assertEquals("protocolOptions must be a strict JSON object", error.getMessage());
    }

    IllegalArgumentException duplicate =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProviderProtocolOptions("{\"sensitiveField\":1,\"sensitiveField\":2}"));
    assertEquals("protocolOptions must be a strict JSON object", duplicate.getMessage());

    IllegalArgumentException trailing =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProviderProtocolOptions("{\"a\":{\"secret\":\"top-secret\"}} {\"b\":2}"));
    assertEquals("protocolOptions must be a strict JSON object", trailing.getMessage());

    assertThrows(IllegalArgumentException.class, () -> new ProviderProtocolOptions("{"));
    assertThrows(IllegalArgumentException.class, () -> new ProviderProtocolOptions("{\"a\":}"));
  }

  /** 超限 payload 必须在构造期失败，消息只含上限本身，绝不回显超限内容。 */
  @Test
  void rejectsOversizedPayloadWithoutEchoingPayload() {
    String marker = "top-secret-marker";
    String oversized =
        "{\"a\":\"" + marker + "b".repeat(ProviderProtocolOptions.MAX_UTF8_BYTES) + "\"}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new ProviderProtocolOptions(oversized));

    assertFalse(error.getMessage().contains(marker));
    assertTrue(error.getMessage().contains(String.valueOf(ProviderProtocolOptions.MAX_UTF8_BYTES)));

    // 多字节字符按 UTF-8 字节数计上界，而不是字符数。
    String multiByte =
        "{\"a\":\"" + "中".repeat(ProviderProtocolOptions.MAX_UTF8_BYTES / 3 + 1) + "\"}";
    assertThrows(IllegalArgumentException.class, () -> new ProviderProtocolOptions(multiByte));
  }

  /** 无法序列化为 JSON 的值必须明确失败，而不是被静默丢弃或转义成字符串。 */
  @Test
  void rejectsNonJsonValuesWhenBuiltFromParsedObject() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bad", new Object());

    assertThrows(IllegalArgumentException.class, () -> ProviderProtocolOptions.of(raw));
  }
}
