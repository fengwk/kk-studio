package fun.fengwk.kkstudio.harness.model;

import static fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions.InvalidReason.MALFORMED;
import static fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions.InvalidReason.TOO_LARGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

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

  /** 非 object、严格 JSON 语法错误、重复键、trailing token 都明确失败，且消息不回显 payload。 */
  @Test
  void rejectsNonObjectsDuplicatesTrailingTokensAndMalformedJsonWithoutEchoingPayload() {
    for (String notObject : List.of("[]", "\"text\"", "42", "true", "null")) {
      ProviderProtocolOptions.InvalidOptionsException error =
          assertThrows(
              ProviderProtocolOptions.InvalidOptionsException.class,
              () -> new ProviderProtocolOptions(notObject));
      assertEquals("protocolOptions must be a strict JSON object", error.getMessage());
      assertEquals(MALFORMED, error.reason());
    }

    ProviderProtocolOptions.InvalidOptionsException duplicate =
        assertThrows(
            ProviderProtocolOptions.InvalidOptionsException.class,
            () -> new ProviderProtocolOptions("{\"sensitiveField\":1,\"sensitiveField\":2}"));
    assertEquals("protocolOptions must be a strict JSON object", duplicate.getMessage());
    assertEquals(MALFORMED, duplicate.reason());

    ProviderProtocolOptions.InvalidOptionsException trailing =
        assertThrows(
            ProviderProtocolOptions.InvalidOptionsException.class,
            () -> new ProviderProtocolOptions("{\"a\":{\"secret\":\"top-secret\"}} {\"b\":2}"));
    assertEquals("protocolOptions must be a strict JSON object", trailing.getMessage());
    assertEquals(MALFORMED, trailing.reason());

    assertThrows(
        ProviderProtocolOptions.InvalidOptionsException.class,
        () -> new ProviderProtocolOptions("{"));
    assertThrows(
        ProviderProtocolOptions.InvalidOptionsException.class,
        () -> new ProviderProtocolOptions("{\"a\":}"));
  }

  @Test
  void extractsAndReplacesNamedToolDefinitionsWithoutChangingOtherOptions() {
    ProviderProtocolOptions source =
        new ProviderProtocolOptions(
            "{\"max_tokens\":1000,\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\",\"max_uses\":3}]}"
                + " ");
    var originalTool = source.toolDefinitions().getFirst();
    ProviderProtocolOptions updated =
        source.withToolDefinitions(
            List.of(
                new ProviderProtocolOptions.ToolDefinition(
                    "code_execution",
                    "{\"type\":\"code_execution_20250522\",\"name\":\"code_execution\"}")));

    assertEquals("web_search", originalTool.name());
    assertEquals(1000, updated.copyOptions().path("max_tokens").asInt());
    assertEquals("code_execution", updated.toolDefinitions().getFirst().name());
  }

  /** 超限 payload 必须在构造期失败，消息只含上限本身，绝不回显超限内容。 */
  @Test
  void rejectsOversizedPayloadWithoutEchoingPayload() {
    String marker = "top-secret-marker";
    String oversized =
        "{\"a\":\"" + marker + "b".repeat(ProviderProtocolOptions.MAX_UTF8_BYTES) + "\"}";

    ProviderProtocolOptions.InvalidOptionsException error =
        assertThrows(
            ProviderProtocolOptions.InvalidOptionsException.class,
            () -> new ProviderProtocolOptions(oversized));

    assertFalse(error.getMessage().contains(marker));
    assertTrue(error.getMessage().contains(String.valueOf(ProviderProtocolOptions.MAX_UTF8_BYTES)));
    assertEquals(TOO_LARGE, error.reason());

    // 多字节字符按 UTF-8 字节数计上界，而不是字符数。
    String multiByte =
        "{\"a\":\"" + "中".repeat(ProviderProtocolOptions.MAX_UTF8_BYTES / 3 + 1) + "\"}";
    ProviderProtocolOptions.InvalidOptionsException multiByteError =
        assertThrows(
            ProviderProtocolOptions.InvalidOptionsException.class,
            () -> new ProviderProtocolOptions(multiByte));
    assertEquals(TOO_LARGE, multiByteError.reason());
  }
}
